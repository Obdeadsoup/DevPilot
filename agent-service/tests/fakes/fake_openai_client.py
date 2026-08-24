"""只模拟 SDK Client 外形，不访问网络。"""


class FakeCompletions:
    def __init__(self, result: object) -> None:
        self._result = result
        self.requests: list[dict[str, object]] = []

    def create(self, **request: object) -> object:
        self.requests.append(request)
        if isinstance(self._result, Exception):
            raise self._result
        return self._result


class FakeChat:
    def __init__(self, result: object) -> None:
        self.completions = FakeCompletions(result)


class FakeOpenAIClient:
    def __init__(self, result: object) -> None:
        self.chat = FakeChat(result)

