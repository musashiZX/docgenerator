"""Word (.docx) editor agent powered by Google Gemini.

Same tool set as `doc_editor.py`, but uses Gemini (free tier available).

Setup:
  1. Get a key at https://aistudio.google.com/apikey
  2. Put it in .env as:  GEMINI_API_KEY=your-key-here
  3. pip install google-genai
  4. python doc_editor_gemini.py my-doc.docx [--model gemini-2.5-flash]
"""

import argparse
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from google import genai
from google.genai import types

from doc_editor import TOOLS as ANTHROPIC_TOOLS, DocEditor, dispatch

load_dotenv()

DEFAULT_MODEL = "gemini-2.5-flash"

SYSTEM_PROMPT = """You are a Word-document editing agent. The user has opened a \
.docx file and you help edit it through chat. The user may write to you in any \
language (English, Chinese, etc.); the *document language* is whatever the user \
asks for — if unspecified, match the document's existing language.

CORE RULES
1. ALWAYS call `read_document` FIRST at the start of every turn to see the \
current state — paragraph indices, headings, and existing content.
2. When the user asks to add/edit content under a specific section (e.g. \
"add to Education", "在工作经历里加一条", "丰富教育经历"), you MUST:
   a) locate the heading paragraph for that section in the read_document output,
   b) find where that section ends (next heading of equal or higher level, or \
      end of document),
   c) insert the new content INSIDE that section using `insert_paragraph` with \
      the correct index — never just `append_paragraph` to the end of the doc, \
      because that drops content into whatever the last section happens to be.
3. Paragraph indices are 0-based and shift after every insert/delete. After \
2+ positional edits in a row, call `read_document` again before the next one.
4. Respect the document's structure: a Heading 2 belongs under a Heading 1; \
list items use 'List Bullet' or 'List Number' styles; body text uses 'Normal'.

CONTENT QUALITY
- When the user says "enrich", "expand", "add details", "丰富", "补充", \
"展开", or similar, produce SUBSTANTIVE content — not a single line. For each \
item you add, include the relevant supporting details a reader would expect:
  • Education entry → years, university, department/major, location, plus 1–3 \
    bullets of relevant courses, GPA/honors, thesis topic, or activities.
  • Work entry → years, company, location, role, then 2–4 bullets describing \
    responsibilities and measurable impact.
  • Project entry → name, dates, stack/role, then 2–3 bullets on what was \
    built and the outcome.
- If the user gave you partial facts (e.g. only years + university + major), \
fill in plausible, professional placeholder bullets and clearly mark anything \
fabricated, OR ask one concise clarifying question before writing — pick \
whichever is faster for the user.
- Match the existing document's tone, language, and formatting. If the doc \
uses bullets for an existing section, your additions should too.
- Never leave "[add details here]" placeholders in content you wrote yourself.

OUTPUT
- After editing, give a SHORT (1–3 sentence) summary of what changed AND where \
(which section, which paragraph indices). Don't dump the full new content back.
- Available paragraph styles: 'Normal', 'Title', 'Heading 1', 'Heading 2', \
'Heading 3', 'List Bullet', 'List Number', 'Quote'.
"""


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


def to_gemini_tools(anthropic_tools: list[dict]) -> types.Tool:
    declarations = []
    for t in anthropic_tools:
        schema = t.get("input_schema") or {"type": "object", "properties": {}}
        # Gemini requires non-empty parameters or omitted entirely.
        if not schema.get("properties"):
            declarations.append(
                types.FunctionDeclaration(
                    name=t["name"],
                    description=t.get("description", ""),
                )
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


GEMINI_TOOL = to_gemini_tools(ANTHROPIC_TOOLS)


def run_turn(client: genai.Client, model: str, editor: DocEditor, history: list) -> None:
    """Drive the function-call loop until the model stops requesting tools."""
    config = types.GenerateContentConfig(
        system_instruction=SYSTEM_PROMPT,
        tools=[GEMINI_TOOL],
    )

    max_steps = 20
    for _ in range(max_steps):
        response = client.models.generate_content(
            model=model,
            contents=history,
            config=config,
        )

        candidate = response.candidates[0]
        parts = candidate.content.parts or []

        history.append(candidate.content)

        function_calls = [p.function_call for p in parts if p.function_call]
        text_chunks = [p.text for p in parts if getattr(p, "text", None)]

        if text_chunks:
            print(f"Assistant: {''.join(text_chunks)}")

        if not function_calls:
            return

        function_response_parts = []
        for call in function_calls:
            args = dict(call.args) if call.args else {}
            preview = json.dumps(args, default=str)
            if len(preview) > 140:
                preview = preview[:137] + "..."
            print(f"  -> {call.name}({preview})")
            result = dispatch(editor, call.name, args)
            function_response_parts.append(
                types.Part.from_function_response(
                    name=call.name,
                    response={"result": result},
                )
            )

        history.append(types.Content(role="user", parts=function_response_parts))

    print("[Stopped: hit max tool-call steps without a final reply]")


def chat(path: Path, model: str) -> None:
    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY is not set.")
        print("Put it in .env as:  GEMINI_API_KEY=your-key-here")
        sys.exit(1)

    editor = DocEditor(path)
    client = genai.Client(api_key=api_key)

    history: list = []

    print(f"Gemini Word agent ready. Editing: {path.resolve()}")
    print(f"Model: {model}. Type 'exit' to leave, 'reset' to clear history.\n")

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
            history = []
            print("[Conversation history cleared]\n")
            continue

        history.append(types.Content(role="user", parts=[types.Part(text=user_input)]))
        try:
            run_turn(client, model, editor, history)
        except Exception as e:  # noqa: BLE001
            print(f"\n[Error: {e}]\n")
            # Drop the failed user turn so we don't resend it on the next try.
            if history and getattr(history[-1], "role", None) == "user":
                history.pop()


def main() -> None:
    parser = argparse.ArgumentParser(description="Edit a .docx file via Google Gemini.")
    parser.add_argument("path", help="Path to the .docx file (created if missing).")
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"Gemini model name (default: {DEFAULT_MODEL}).",
    )
    args = parser.parse_args()

    path = Path(args.path)
    if path.suffix.lower() != ".docx":
        print("Error: file must have a .docx extension.")
        sys.exit(1)

    chat(path, args.model)


if __name__ == "__main__":
    main()
