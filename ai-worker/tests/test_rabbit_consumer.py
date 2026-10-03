import threading
from types import SimpleNamespace

from ai_worker.infrastructure.rabbit_consumer import RabbitConsumer


class Loop:
    def __init__(self):
        self.callbacks = []
    def add_callback_threadsafe(self, callback):
        self.callbacks.append(callback)


def test_ack_runs_only_on_the_io_thread_after_processing():
    from test_execution import decode, message
    import json
    node = message()
    calls = []
    class Processor:
        def process(self, request, stopping):
            calls.append(("processed", threading.get_ident()))
    consumer = RabbitConsumer(Processor(), {})
    loop = Loop()
    consumer.connection = SimpleNamespace(ioloop=loop)
    channel = SimpleNamespace(is_open=True, basic_ack=lambda **_: calls.append(("ack", threading.get_ident())))
    properties = SimpleNamespace(message_id=node["eventId"], content_type="application/json",
                                  type="InvoiceAnalysisRequested", content_encoding="UTF-8")
    consumer._delivery(channel, SimpleNamespace(delivery_tag=1), properties, json.dumps(node).encode())
    consumer.job.join(timeout=2)
    assert not consumer.job.is_alive()
    assert [name for name, _ in calls] == ["processed"]
    assert len(loop.callbacks) == 1
    loop.callbacks[0]()
    assert calls[0][1] != calls[1][1] == threading.get_ident()


def test_shutdown_fences_an_already_scheduled_ack():
    consumer = RabbitConsumer(None, {})
    consumer.stopping.set()
    calls = []
    consumer._settle(SimpleNamespace(is_open=True, basic_ack=lambda **_: calls.append(True)), 1, None)
    assert calls == []
