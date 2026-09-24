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

The surrounding white margin is trimmed before the image goes into the deck; any
image editor will do the same job. Keep the rendered width above 1000 px so the
mockups stay sharp when projected.

## Editing the deck

The `.pptx` is a normal PowerPoint file — editing it by hand is fine if that is
quicker. Only edit `build_deck.py` when the change should survive a rebuild, and
re-run the script afterwards.
