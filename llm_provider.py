"""Provider-neutral LLM layer.

Supports two back-ends behind one interface:

  * Google Gemini  (``GEMINI_API_KEY`` / ``GOOGLE_API_KEY``)
  * OpenAI GPT     (``OPENAI_API_KEY``)

Exposes ``run_agent_loop`` — a function-calling (tool) loop where history is
kept in a provider-neutral list so conversations survive model switches.

Neutral history entries
-----------------------
    {"role": "user", "content": "<text>"}
    {"role": "assistant", "content": "<text>", "tool_calls": [{"id","name","args"}]}
    {"role": "tool_batch", "responses": [{"id","name","content"}]}
"""

from __future__ import annotations

import json
import os
from typing import Callable, Optional

# ---------------------------------------------------------------------------
# Model catalogue
# ---------------------------------------------------------------------------

GEMINI_MODELS = [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-flash-latest",
    "gemini-pro-latest",
]

OPENAI_MODELS = [
    "gpt-5",
    "gpt-5-mini",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4o",
    "gpt-4o-mini",
]

ALL_MODELS = GEMINI_MODELS + OPENAI_MODELS


def provider_name_for_model(model: str) -> str:
    """Return "openai" or "gemini" for a given model name."""
    m = (model or "").lower()
    if m in (x.lower() for x in OPENAI_MODELS):
        return "openai"
    if m in (x.lower() for x in GEMINI_MODELS):
        return "gemini"
    if m.startswith("gpt") or m.startswith("o1") or m.startswith("o3") or m.startswith("o4"):
        return "openai"
    return "gemini"


def missing_key_message(model: str) -> str:
    if provider_name_for_model(model) == "openai":
        return "Set OPENAI_API_KEY in .env first."
    return "Set GEMINI_API_KEY in .env first."


# ---------------------------------------------------------------------------
# Tool-schema helpers
# ---------------------------------------------------------------------------

def _normalize_schema(tool: dict) -> dict:
    """Tools come in two flavours (`input_schema` or `parameters`)."""
    return (
        tool.get("input_schema")
        or tool.get("parameters")
        or {"type": "object", "properties": {}}
    )


def _strip_unsupported(schema):
    """Gemini's schema is a subset of JSONSchema — drop fields it rejects."""
    if isinstance(schema, dict):
        cleaned = {}
        for k, v in schema.items():
            if k in {"additionalProperties", "$schema", "title"}:
                continue
            cleaned[k] = _strip_unsupported(v)
        return cleaned
    if isinstance(schema, list):
        return [_strip_unsupported(v) for v in schema]
    return schema


# ---------------------------------------------------------------------------
# Base provider
# ---------------------------------------------------------------------------

class Provider:
    name = "base"

    def __init__(self, model: str):
        self.model = model

    def _chat(self, system: Optional[str], tools: list[dict], history: list[dict]):
        """Return ``(text, tool_calls)`` for one model turn.

        ``tool_calls`` is a list of ``{"id", "name", "args"}`` dicts.
        """
        raise NotImplementedError

    def complete(self, system: Optional[str], user_text: str) -> str:
        """Single-shot text completion with no tools. Returns the model's reply."""
        raise NotImplementedError

    def run_agent_loop(
        self,
        *,
        system: Optional[str],
        tools: list[dict],
        history: list[dict],
        user_text: str,
        dispatch: Callable[[str, dict], str],
        max_steps: int = 20,
    ) -> tuple[str, list[dict]]:
        """Drive the tool loop until the model stops requesting tools.

        ``history`` is mutated in place (persists in session state).
        ``dispatch(name, args)`` runs a tool and returns its string result.
        Returns ``(final_text, tool_log)``.
        """
        history.append({"role": "user", "content": user_text})

        final_text = ""
        tool_log: list[dict] = []

        for _ in range(max_steps):
            text, tool_calls = self._chat(system, tools, history)

            assistant_entry: dict = {"role": "assistant", "content": text}
            if tool_calls:
                assistant_entry["tool_calls"] = tool_calls
            history.append(assistant_entry)

            if text:
                final_text += text

            if not tool_calls:
                break

            responses = []
            for call in tool_calls:
                result = dispatch(call["name"], call["args"])
                tool_log.append({"name": call["name"], "args": call["args"], "result": result})
                responses.append({"id": call["id"], "name": call["name"], "content": result})
            history.append({"role": "tool_batch", "responses": responses})

        return final_text or "_(no reply)_", tool_log


# ---------------------------------------------------------------------------
# Gemini provider
# ---------------------------------------------------------------------------

class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, api_key: str, model: str):
        super().__init__(model)
        from google import genai
        self.client = genai.Client(api_key=api_key)

    def _to_contents(self, history: list[dict]):
        from google.genai import types

        contents = []
        for m in history:
            role = m["role"]
            if role == "user":
                contents.append(
                    types.Content(role="user", parts=[types.Part(text=m["content"])])
                )
            elif role == "assistant":
                parts = []
                if m.get("content"):
                    parts.append(types.Part(text=m["content"]))
                for c in m.get("tool_calls", []):
                    parts.append(
                        types.Part(
                            function_call=types.FunctionCall(
                                name=c["name"], args=c.get("args") or {}
                            )
                        )
                    )
                if not parts:
                    parts.append(types.Part(text=""))
                contents.append(types.Content(role="model", parts=parts))
            elif role == "tool_batch":
                parts = [
                    types.Part.from_function_response(
                        name=r["name"], response={"result": r["content"]}
                    )
                    for r in m["responses"]
                ]
                contents.append(types.Content(role="user", parts=parts))
        return contents

    def _to_tool(self, tools: list[dict]):
        from google.genai import types

        declarations = []
        for t in tools:
            schema = _normalize_schema(t)
            if not schema.get("properties"):
                declarations.append(
                    types.FunctionDeclaration(name=t["name"], description=t.get("description", ""))
                )
            else:
                declarations.append(
                    types.FunctionDeclaration(
                        name=t["name"],
                        description=t.get("description", ""),
                        parameters=_strip_unsupported(schema),
                    )
                )
        return types.Tool(function_declarations=declarations)

    def _chat(self, system, tools, history):
        from google.genai import types

        config = types.GenerateContentConfig(
            system_instruction=system,
            tools=[self._to_tool(tools)],
        )
        response = self.client.models.generate_content(
            model=self.model,
            contents=self._to_contents(history),
            config=config,
        )
        candidate = response.candidates[0]
        parts = candidate.content.parts or []

        text = "".join(p.text for p in parts if getattr(p, "text", None))
        tool_calls = []
        for p in parts:
            fc = getattr(p, "function_call", None)
            if fc:
                tool_calls.append({
                    "id": fc.name,
                    "name": fc.name,
                    "args": dict(fc.args) if fc.args else {},
                })
        return text, tool_calls

    def complete(self, system: Optional[str], user_text: str) -> str:
        from google.genai import types

        config = types.GenerateContentConfig(system_instruction=system)
        response = self.client.models.generate_content(
            model=self.model,
            contents=[types.Content(role="user", parts=[types.Part(text=user_text)])],
            config=config,
        )
        candidate = response.candidates[0]
        parts = candidate.content.parts or []
        return "".join(p.text for p in parts if getattr(p, "text", None))


# ---------------------------------------------------------------------------
# OpenAI provider
# ---------------------------------------------------------------------------

class OpenAIProvider(Provider):
    name = "openai"

    def __init__(self, api_key: str, model: str):
        super().__init__(model)
        from openai import OpenAI
        self.client = OpenAI(api_key=api_key)

    @property
    def _is_reasoning_model(self) -> bool:
        m = self.model.lower()
        return m.startswith("o1") or m.startswith("o3") or m.startswith("o4") or m.startswith("gpt-5")

    def _to_messages(self, system, history: list[dict]) -> list[dict]:
        msgs: list[dict] = []
        if system:
            msgs.append({"role": "system", "content": system})
        for m in history:
            role = m["role"]
            if role == "user":
                msgs.append({"role": "user", "content": m["content"]})
            elif role == "assistant":
                entry: dict = {"role": "assistant", "content": m.get("content") or None}
                if m.get("tool_calls"):
                    entry["tool_calls"] = [
                        {
                            "id": c["id"],
                            "type": "function",
                            "function": {
                                "name": c["name"],
                                "arguments": json.dumps(c.get("args") or {}),
                            },
                        }
                        for c in m["tool_calls"]
                    ]
                msgs.append(entry)
            elif role == "tool_batch":
                for r in m["responses"]:
                    msgs.append({
                        "role": "tool",
                        "tool_call_id": r["id"],
                        "content": r["content"],
                    })
        return msgs

    def _to_tools(self, tools: list[dict]) -> list[dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "parameters": _normalize_schema(t),
                },
            }
            for t in tools
        ]

    def _chat(self, system, tools, history):
        response = self.client.chat.completions.create(
            model=self.model,
            messages=self._to_messages(system, history),
            tools=self._to_tools(tools),
        )
        msg = response.choices[0].message
        text = msg.content or ""
        tool_calls = []
        for tc in msg.tool_calls or []:
            try:
                args = json.loads(tc.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}
            tool_calls.append({"id": tc.id, "name": tc.function.name, "args": args})
        return text, tool_calls

    def complete(self, system: Optional[str], user_text: str) -> str:
        msgs: list[dict] = []
        if system:
            msgs.append({"role": "system", "content": system})
        msgs.append({"role": "user", "content": user_text})
        response = self.client.chat.completions.create(
            model=self.model,
            messages=msgs,
        )
        return response.choices[0].message.content or ""


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------

def get_provider(model: str) -> Optional[Provider]:
    """Build the right provider for ``model``, or ``None`` if its key is unset."""
    name = provider_name_for_model(model)
    if name == "openai":
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            return None
        return OpenAIProvider(api_key, model)

    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        return None
    return GeminiProvider(api_key, model)
