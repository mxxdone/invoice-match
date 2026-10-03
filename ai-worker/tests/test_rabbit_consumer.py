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


def test_poison_is_redacted_and_ack_waits_for_confirm():
    import hashlib
    import json
    import pika
    calls = []
    consumer = RabbitConsumer(None, {"exchange": "analysis", "routing_key": "parse"})
    consumer.connection = SimpleNamespace(ioloop=SimpleNamespace(call_later=lambda *_: None))
    consumer.channel = SimpleNamespace(basic_publish=lambda **kwargs: calls.append(("publish", kwargs)),
                                     basic_ack=lambda **kwargs: calls.append(("ack", kwargs)))
    raw = b'secret=do-not-retain'
    consumer._quarantine(consumer.channel, 7, raw, "INVALID_MESSAGE")
    assert len(calls) == 1
    stored = json.loads(calls[0][1]["body"])
    assert stored["payloadHash"] == hashlib.sha256(raw).hexdigest()
    assert stored["sizeBytes"] == len(raw)
    assert raw not in calls[0][1]["body"]
    assert calls[0][1]["mandatory"] is True
    consumer._confirmed(SimpleNamespace(method=pika.spec.Basic.Ack(delivery_tag=1)))
    assert calls[1] == ("ack", {"delivery_tag": 7, "multiple": False})


def test_return_or_nack_cannot_ack_original_delivery():
    import pika
    for returned in [False, True]:
        calls = []
        consumer = RabbitConsumer(None, {})
        consumer.pending_quarantine = 9
        consumer.returned = returned
        consumer._stop = lambda code: calls.append(code)
        consumer.channel = SimpleNamespace(basic_ack=lambda **_: calls.append("unsafe-ack"))
        method = pika.spec.Basic.Ack(delivery_tag=1) if returned else pika.spec.Basic.Nack(delivery_tag=1)
        consumer._confirmed(SimpleNamespace(method=method))
        assert calls == ["QUARANTINE_NOT_CONFIRMED"]
