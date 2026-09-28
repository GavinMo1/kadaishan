# Surface brief — kadaishan 全部界面（iOS 质感重设计）

Scope: 图片/视频卡片流、结算面板、回收站页、设置页、权限引导与主 chrome。
Visitor mode: **Operate**（清理工具，扫读与效率优先）。

## Direction contract

THESIS: 相册清理器的 iOS 质感 = 内容全幅、chrome 悬浮半透明、每次操作有弹簧动效与触觉回执；照片占据白画布的中心，界面元素全部退后。拒绝的默认排版：不透明白色顶栏压住内容 + 双重标题系统 + 无反馈的滑动。

OWN-WORLD: 纸白画布（#FFF）+ 米白分组容器（#F5F5F7）；唯一强调色 Action Blue #0066CC 承担一切可点信号；圆角四档制 5/8/11/18 + pill；控件 = 药丸主键 / 半透圆片 / 分段页签 / 圆环勾选 / 分组列表行；阴影只给影像。

STORY: 用户单手滑动整理相册——滑卡有跟手位移与倾斜、判定有触觉 tick、卡片沿手势方向退场；危险动作（永久删除）红色药丸 + 系统二次确认；一切结果三态可追。

FIRST VIEWPORT: 照片卡片占满竖向空间（顶部留悬浮 chrome 一行：分段页签 + 回收站/设置），右上半透圆片「信息/撤回」，底左文件名圆牌、底中「3 / 20」计数圆牌；顶栏点照片即隐，内容不重排。

FORM: 继承既有 Daylight 世界（DESIGN.md 佚失后由代码确立），按 iOS 精致感深化执行层；无概念轮换（established world · inherit）。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## 约束（不可违背）

- PRD 交互口径不动：手势映射/阈值、结算三态、分批 ≤50、永久上限 30、文案语义。
- 手势热路径零分配（跟手位移用 graphicsLayer 状态读取，不触发重组）。
- 零联网、零新增依赖；Material3 结构保证（返回手势、insets、触控 ≥48dp）。
- 本机无构建工具链：code-led，截图验收（含深浅/字号缩放）延迟到可构建机器。
