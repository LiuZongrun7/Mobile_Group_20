# Outline slides — sources

The presentation `../TokenTrail_Project_Outline_Slides.pptx` is generated from
`build_deck.py`. Its content mirrors `../TokenTrail_Project_Outline.tex`, so the
deck and the written summary stay in step.

## Rebuild

```sh
python3 -m venv .venv
.venv/bin/pip install python-pptx      # Pillow comes with it
.venv/bin/python build_deck.py         # writes ../TokenTrail_Project_Outline_Slides.pptx
```

The script writes the `.pptx` next to this folder, so run it from anywhere.

The committed `.pdf` next to it is the `.pptx` exported through LibreOffice:

```sh
soffice --headless --convert-to pdf --outdir /tmp/deck ../TokenTrail_Project_Outline_Slides.pptx
```

Re-run both whenever the deck changes — the PDF is what gets read, the PPTX is
what gets edited.

## The four figures

`fig_*.tex` are the sources of the images embedded in the deck. They are our own
TikZ drawings, not third-party art, so no licence applies.

| Source | Image | Used on |
| --- | --- | --- |
| `fig_game.tex` | `fig_game.png` | Title slide, "New feature A" |
| `fig_agent.tex` | `fig_agent.png` | "New feature B" |
| `fig_ui.tex` | `fig_ui.png` | "Proposed interface" |
| `fig_flow.tex` | `fig_flow.png` | "How it is implemented" |

Each is a standalone one-page document. Rebuild one with XeLaTeX and rasterise it:

```sh
PATH="/Library/TeX/texbin:$PATH" xelatex -interaction=nonstopmode fig_game.tex
pdftoppm -png -r 400 fig_game.pdf fig_game      # then trim the white margin
```

The surrounding white margin is trimmed before the image goes into the deck. The
trim is *crop to the drawn content, then pad back out* — and **the pad is not the
same on both axes**, so `-trim` plus a uniform border will change the image size
and shift the figure in the deck:

| Source | Pad (x, y) at 400 dpi | Result |
| --- | --- | --- |
| `fig_ui.tex` | 11, 11 | 2393 × 998 |
| `fig_agent.tex` | 22, 11 | 1028 × 1824 |
| `fig_flow.tex` | 24, 12 | 2487 × 263 |

The pads are what the committed PNGs were made with; matching them keeps the
figure the same size in the deck, because `build_deck.py` scales by width for
`fig_ui` and by height for the other two. `fig_game` is unchanged so far. Keep
the rendered width above 1000 px so the mockups stay sharp when projected.

The `fig_*.pdf` and `fig_*.aux` / `.log` files XeLaTeX leaves behind are build
by-products — only the `.tex` and the trimmed `.png` are committed.

## Editing the deck

The `.pptx` is a normal PowerPoint file — editing it by hand is fine if that is
quicker. Only edit `build_deck.py` when the change should survive a rebuild, and
re-run the script afterwards.
