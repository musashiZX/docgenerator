"""Local Word (.docx) editor agent powered by Ollama.

Same tool set as `doc_editor.py`, but runs entirely on your machine via
Ollama (https://ollama.com). Free, offline, no API key.

Setup:
  1. Install Ollama: https://ollama.com/download
  2. Pull a tool-capable model. Recommended (best tool use first):
       ollama pull qwen2.5:7b
       ollama pull llama3.1:8b
  3. pip install ollama
  4. python doc_editor_ollama.py my-doc.docx [--model qwen2.5:7b]
"""

import argparse
import json
import sys
from pathlib import Path

import ollama

from doc_editor import TOOLS as ANTHROPIC_TOOLS, DocEditor, dispatch

DEFAULT_MODEL = "qwen2.5:7b"

SYSTEM_PROMPT = """You are a Word-document editing agent. The user has opened a \
.docx file and you help edit it through chat.

You have tools to read and modify the document. Rules:
- Call `read_document` first whenever you need to know the document's current state.
- Make focused edits — don't rewrite content the user didn't ask you to change.
- Paragraph indices are 0-based and shift after insertions/deletions; re-read \
when making several positional edits in sequence.
- After completing edits, briefly tell the user what you changed.
- Available styles include 'Normal', 'Title', 'Heading 1', 'Heading 2', \
'Heading 3', 'List Bullet', 'List Number', 'Quote'.
"""


def to_ollama_tools(anthropic_tools: list[dict]) -> list[dict]:
    """Convert Anthropic-style tool specs to Ollama (OpenAI) format."""
    converted = []
    for t in anthropic_tools:
        schema = t.get("input_schema") or {"type": "object", "properties": {}}
        converted.append(
            {
                "type": "function",
                "function": {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "parameters": schema,
                },
            }
        )
    return converted


OLLAMA_TOOLS = to_ollama_tools(ANTHROPIC_TOOLS)


def coerce_args(raw) -> dict:
    """Some local models hand back arguments as a JSON string instead of a dict."""
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def run_turn(client: ollama.Client, model: str, editor: DocEditor, history: list[dict]) -> None:
    """Drive the tool-call loop until the model stops requesting tools."""
    max_steps = 20
    for _ in range(max_steps):
        response = client.chat(
            model=model,
            messages=history,
            tools=OLLAMA_TOOLS,
        )
        msg = response["message"]
        text = msg.get("content") or ""
        tool_calls = msg.get("tool_calls") or []

        # Record the assistant turn (preserve tool_calls so the model sees its own decisions).
        history.append(
            {
                "role": "assistant",
                "content": text,
                **({"tool_calls": tool_calls} if tool_calls else {}),
            }
        )

        if text:
            print(f"Assistant: {text}")

        if not tool_calls:
            return

        for call in tool_calls:
            fn = call["function"]
            name = fn["name"]
            args = coerce_args(fn.get("arguments"))
            preview = json.dumps(args)
            if len(preview) > 140:
                preview = preview[:137] + "..."
            print(f"  -> {name}({preview})")
            result = dispatch(editor, name, args)
            history.append(
                {
                    "role": "tool",
                    "name": name,
                    "content": result,
                }
            )

    print("[Stopped: hit max tool-call steps without a final reply]")


def chat(path: Path, model: str, host: str | None) -> None:
    editor = DocEditor(path)
    client = ollama.Client(host=host) if host else ollama.Client()

    # Verify the model is available locally.
    try:
        client.show(model)
    except Exception as e:  # noqa: BLE001
        print(f"ERROR: model '{model}' not available via Ollama ({e}).")
        print(f"Try:  ollama pull {model}")
        sys.exit(1)

    history: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]

    print(f"Local Word agent ready. Editing: {path.resolve()}")
    print(f"Model: {model} (via Ollama). Type 'exit' to leave, 'reset' to clear history.\n")

    while True:
        try:
            user_input = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye.")
            return

        if not user_input:
            continue
        if user_input.lower() in {"exit", "quit"}:
            print("Goodbye.")
            return
        if user_input.lower() == "reset":
            history = [{"role": "system", "content": SYSTEM_PROMPT}]
            print("[Conversation history cleared]\n")
            continue

        history.append({"role": "user", "content": user_input})
        try:
            run_turn(client, model, editor, history)
        except ollama.ResponseError as e:
            print(f"\n[Ollama error: {e}]\n")
            history.pop()
        except Exception as e:  # noqa: BLE001
            print(f"\n[Error: {e}]\n")
            history.pop()


def main() -> None:
    parser = argparse.ArgumentParser(description="Edit a .docx file via local Ollama model.")
    parser.add_argument("path", help="Path to the .docx file (created if missing).")
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Ollama model name (default: {DEFAULT_MODEL}). Must support tool calling.",
    )
    parser.add_argument(
        "--host",
        default=None,
        help="Ollama server URL (default: http://localhost:11434).",
    )
    args = parser.parse_args()

    path = Path(args.path)
    if path.suffix.lower() != ".docx":
        print("Error: file must have a .docx extension.")
        sys.exit(1)

    chat(path, args.model, args.host)


if __name__ == "__main__":
    main()
