"""Character budget configuration; not a tokenizer or long-term memory."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ContextBudget:
    max_context_chars: int = 24_000
    max_tool_result_chars: int = 6_000
    max_recent_messages: int = 20
    reserved_output_chars: int = 4_000
    max_rag_evidence_chars: int = 4_000
    max_memory_chars: int = 1_200
    max_summary_chars: int = 1_000

    def __post_init__(self) -> None:
        for value in (
            self.max_context_chars,
            self.max_tool_result_chars,
            self.max_recent_messages,
            self.reserved_output_chars,
            self.max_rag_evidence_chars,
            self.max_memory_chars,
            self.max_summary_chars,
        ):
            if type(value) is not int or value < 1:
                raise ValueError("context budget values must be positive integers")
        if self.reserved_output_chars >= self.max_context_chars:
            raise ValueError("output reservation must be smaller than total context budget")

    @property
    def input_chars(self) -> int:
        return self.max_context_chars - self.reserved_output_chars
