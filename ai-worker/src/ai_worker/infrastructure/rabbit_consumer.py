"""Pika I/O loop owns the channel; one processing thread owns slow work."""
from __future__ import annotations

import signal
import threading

import pika

from ai_worker.application.execution import ProcessDelivery, Request, WorkerFailure


class RabbitConsumer:
    def __init__(self, processor: ProcessDelivery, settings: dict[str, str]) -> None:
        self.processor = processor
        self.settings = settings
        self.stopping = threading.Event()
        self.failure: str | None = None
        self.connection = None
        self.channel = None
        self.job: threading.Thread | None = None
        self.ready = False

    def run(self) -> None:
        p = self.settings
        parameters = pika.ConnectionParameters(host=p["host"], port=int(p["port"]), virtual_host=p["vhost"],
                credentials=pika.PlainCredentials(p["username"], p["password"]), heartbeat=10,
                socket_timeout=2, stack_timeout=5, blocked_connection_timeout=5, connection_attempts=1)
        self.connection = pika.SelectConnection(parameters, on_open_callback=self._opened,
                on_open_error_callback=lambda *_: self._stop("BROKER_UNAVAILABLE"),
                on_close_callback=self._closed)
        self.connection.ioloop.call_later(10, lambda: None if self.ready else self._stop("BROKER_START_TIMEOUT"))
        previous = {sig: signal.signal(sig, lambda *_: self._stop()) for sig in (signal.SIGTERM, signal.SIGINT)}
        try:
            self.connection.ioloop.start()
        except Exception:
            self._stop("BROKER_FAILED")
        finally:
            self.stopping.set()
            self.connection.ioloop.close()
            for sig, handler in previous.items():
                signal.signal(sig, handler)
            if self.job is not None:
                self.job.join(timeout=55)
                if self.job.is_alive():
                    self.failure = "SHUTDOWN_TIMEOUT"
            if self.failure:
                raise WorkerFailure(self.failure)

    def _opened(self, connection):
        if self.stopping.is_set():
            connection.close()
            return
        connection.channel(on_open_callback=self._channel_opened)

    def _channel_opened(self, channel):
        self.channel = channel
        channel.add_on_close_callback(lambda *_: self._stop("BROKER_CHANNEL_CLOSED"))
        channel.exchange_declare(exchange=self.settings["exchange"], exchange_type="direct", durable=True,
                callback=lambda _: channel.queue_declare(queue=self.settings["queue"], durable=True,
                callback=lambda _: channel.queue_bind(queue=self.settings["queue"], exchange=self.settings["exchange"],
                routing_key=self.settings["routing_key"], callback=lambda _: channel.basic_qos(prefetch_count=1,
                callback=lambda _: channel.basic_consume(queue=self.settings["queue"],
                on_message_callback=self._delivery, auto_ack=False,
                callback=lambda _: setattr(self, "ready", True))))))

    def _delivery(self, channel, method, properties, body):
        if self.stopping.is_set() or self.job is not None and self.job.is_alive():
            self._stop("DELIVERY_SLOT_BUSY")
            return
        try:
            request = Request.decode(body, properties.message_id, properties.content_type,
                                     properties.type, properties.content_encoding)
        except WorkerFailure as exc:
            self._stop(exc.code)
            return

        def process():
            failure = None
            try:
                self.processor.process(request, self.stopping.is_set)
            except WorkerFailure as exc:
                failure = exc.code
            except Exception:
                failure = "WORKER_FAILED"
            if not self.stopping.is_set():
                self.connection.ioloop.add_callback_threadsafe(
                        lambda: self._settle(channel, method.delivery_tag, failure))
        self.job = threading.Thread(target=process, name="analysis-delivery")
        self.job.start()

    def _settle(self, channel, delivery_tag, failure):
        if self.stopping.is_set():
            return
        # Finish the owned thread before ACK admits the next prefetch=1 delivery.
        if self.job is not None:
            self.job.join(timeout=1)
            if self.job.is_alive():
                self._stop("DELIVERY_SLOT_BUSY")
                return
        if failure:
            self._stop(failure)
        elif channel.is_open:
            channel.basic_ack(delivery_tag=delivery_tag, multiple=False)
        else:
            self._stop("BROKER_CHANNEL_CLOSED")

    def _stop(self, failure=None):
        if self.stopping.is_set():
            return
        if failure is not None and self.failure is None:
            self.failure = failure
        self.stopping.set()
        if self.connection is not None:
            self.connection.ioloop.call_later(5, self.connection.ioloop.stop)
            if self.connection.is_open:
                self.connection.close()
            elif not self.connection.is_closing:
                self.connection.ioloop.stop()

    def _closed(self, _connection, reason):
        if not self.stopping.is_set():
            self.failure = "BROKER_CLOSED"
        self.stopping.set()
        self.connection.ioloop.stop()
