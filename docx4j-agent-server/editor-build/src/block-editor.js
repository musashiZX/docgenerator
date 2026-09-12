import { Editor, Extension } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import TextAlign from "@tiptap/extension-text-align";
import TextStyle from "@tiptap/extension-text-style";

// TipTap has no official font-size extension — this is the standard
// community recipe: a global attribute on the textStyle mark, rendered as
// an inline style.
const FontSize = Extension.create({
  name: "fontSize",
  addOptions() {
    return { types: ["textStyle"] };
  },
  addGlobalAttributes() {
    return [
      {
        types: this.options.types,
        attributes: {
          fontSize: {
            default: null,
            parseHTML: (element) => element.style.fontSize || null,
            renderHTML: (attributes) => {
              if (!attributes.fontSize) return {};
              return { style: `font-size: ${attributes.fontSize}` };
            },
          },
        },
      },
    ];
  },
  addCommands() {
    return {
      setFontSize:
        (fontSize) =>
        ({ chain }) =>
          chain().setMark("textStyle", { fontSize }).run(),
      unsetFontSize:
        () =>
        ({ chain }) =>
          chain().setMark("textStyle", { fontSize: null }).removeEmptyTextStyle().run(),
    };
  },
});

const FONT_SIZES = ["8pt", "9pt", "10pt", "11pt", "12pt", "14pt", "16pt", "18pt", "20pt", "24pt", "28pt", "32pt", "36pt", "48pt"];

function buildToolbar(editor) {
  const bar = document.createElement("div");
  bar.className = "be-toolbar";

  const btn = (cmd, label, title) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "be-btn";
    b.dataset.cmd = cmd;
    b.innerHTML = label;
    if (title) b.title = title;
    bar.appendChild(b);
    return b;
  };
  const sep = () => {
    const s = document.createElement("span");
    s.className = "be-sep";
    bar.appendChild(s);
  };

  const boldBtn = btn("bold", "<b>B</b>", "Bold");
  const italicBtn = btn("italic", "<i>I</i>", "Italic");
  const underlineBtn = btn("underline", "<u>U</u>", "Underline");
  sep();
  const sizeSelect = document.createElement("select");
  sizeSelect.className = "be-size";
  sizeSelect.dataset.cmd = "fontsize";
  sizeSelect.innerHTML =
    '<option value="">Size</option>' +
    FONT_SIZES.map((s) => `<option value="${s}">${s.replace("pt", "")}</option>`).join("");
  bar.appendChild(sizeSelect);
  sep();
  const alignLeftBtn = btn("align-left", "L", "Align left");
  const alignCenterBtn = btn("align-center", "C", "Center");
  const alignRightBtn = btn("align-right", "R", "Align right");
  const alignJustifyBtn = btn("align-justify", "J", "Justify");

  // Prevent the button/select from stealing focus away from the editor —
  // without this, mousedown collapses the selection before the click's
  // command can act on it.
  bar.querySelectorAll("button").forEach((b) => {
    b.addEventListener("mousedown", (e) => e.preventDefault());
  });

  bar.querySelectorAll("button[data-cmd]").forEach((b) => {
    b.addEventListener("click", () => {
      const cmd = b.dataset.cmd;
      const chain = editor.chain().focus();
      if (cmd === "bold") chain.toggleBold().run();
      else if (cmd === "italic") chain.toggleItalic().run();
      else if (cmd === "underline") chain.toggleUnderline().run();
      else if (cmd.startsWith("align-")) chain.setTextAlign(cmd.slice(6)).run();
    });
  });
  sizeSelect.addEventListener("mousedown", (e) => e.stopPropagation());
  sizeSelect.addEventListener("change", () => {
    const value = sizeSelect.value;
    sizeSelect.value = "";
    if (!value) return;
    editor.chain().focus().setFontSize(value).run();
  });

  function updateActiveStates() {
    boldBtn.classList.toggle("be-active", editor.isActive("bold"));
    italicBtn.classList.toggle("be-active", editor.isActive("italic"));
    underlineBtn.classList.toggle("be-active", editor.isActive("underline"));
    alignLeftBtn.classList.toggle("be-active", editor.isActive({ textAlign: "left" }));
    alignCenterBtn.classList.toggle("be-active", editor.isActive({ textAlign: "center" }));
    alignRightBtn.classList.toggle("be-active", editor.isActive({ textAlign: "right" }));
    alignJustifyBtn.classList.toggle("be-active", editor.isActive({ textAlign: "justify" }));
  }

  return { bar, updateActiveStates };
}

/**
 * Mounts a real rich-text editor (TipTap/ProseMirror) with its own toolbar
 * into `container`. `options.html` seeds the initial content. Returns
 * { getHTML(), focus(), destroy() } — the caller (app.js) owns saving:
 * it diffs getHTML() against the original to build modify/format mutations
 * through the existing propose/approve pipeline.
 */
function mount(container, options) {
  options = options || {};
  container.innerHTML = "";
  container.classList.add("be-root");

  const contentEl = document.createElement("div");
  contentEl.className = "be-content";
  container.appendChild(contentEl);

  const editor = new Editor({
    element: contentEl,
    extensions: [
      StarterKit.configure({ heading: false, codeBlock: false, blockquote: false, horizontalRule: false }),
      Underline,
      TextStyle,
      FontSize,
      TextAlign.configure({ types: ["paragraph"] }),
    ],
    content: options.html || "",
    autofocus: "end",
  });

  const { bar, updateActiveStates } = buildToolbar(editor);
  container.insertBefore(bar, contentEl);

  editor.on("selectionUpdate", updateActiveStates);
  editor.on("transaction", updateActiveStates);
  if (typeof options.onChange === "function") {
    editor.on("update", () => options.onChange(editor.getHTML()));
  }
  updateActiveStates();

  return {
    getHTML: () => editor.getHTML(),
    focus: () => editor.commands.focus("end"),
    destroy: () => editor.destroy(),
  };
}

window.BlockEditor = { mount };
