# ModelPilot 项目大纲

当前正式版本为英文 4 页 Outline，实施计划覆盖第 4–15 周。

## 文件

- [ModelPilot_Project_Outline.pdf](ModelPilot_Project_Outline.pdf)：阅读与提交用的正式稿。
- [ModelPilot_Project_Outline.tex](ModelPilot_Project_Outline.tex)：可编辑的 LaTeX 源文件。
- [assets/](assets/)：大纲使用的四张前端设计截图。
- [UI_SOURCES.md](UI_SOURCES.md)：截图来源及素材许可说明。

本目录是大纲的正式入口；前端设计图、设计规范和 29 页图册见 [设计稿目录](../modelpilot-ui/README.md)。旧 TokenTrail 文档不代表当前产品方案。

## 编译

在本目录运行两次：

```sh
xelatex -interaction=nonstopmode -halt-on-error ModelPilot_Project_Outline.tex
xelatex -interaction=nonstopmode -halt-on-error ModelPilot_Project_Outline.tex
```

Overleaf 选择 XeLaTeX，并保留 `assets/` 子目录。需要 geometry、fontspec、xcolor、graphicx、tabularx、booktabs、enumitem、fancyhdr、hyperref 等常用包。优先使用 Times New Roman / Arial；缺少时回退到 TeX Gyre Termes / Heros。换字体或编辑后应重新检查是否仍为 4 页，以及有无缺字、溢出。

仓库保留 PDF 成品和 `.tex` 源文件；`.aux`、`.log` 等编译中间文件不提交。
