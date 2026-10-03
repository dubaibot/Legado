# 实施任务清单

> 基于设计文档 v1.0（design.md），2026-10-03

## T1 数据模型与存储
- 新建 `ExploreCatNode`（name/type/kindTitle/url/option/action/children），放 `io.legado.app.data.entities.rule`
- `LocalConfig` 增加 `exploreCats_{sourceUrl}`（树 JSON）与 `exploreCatsEdited_{sourceUrl}`（手动编辑标志）读写
- 筛选设置复用 `InfoMap`（按 sourceUrl 持久化，控件标题→值），不新建存储
- 预填算法：复用分段标题判定（isHeaderKind/isFullRowStyle 迁移至 ViewModel 保留），>=2 生成两级骨架，失败生成空一级+url 平铺二级

## T2 面板渲染抽取 ExploreKindRenderer
- 从 `ExploreAdapter.upKindList` 抽取按 kind 渲染 FlexboxLayout 子控件的逻辑为独立类
- 两种模式：
  - `PANEL_ADD`（筛选页面板）：url/button 点击回调 onKindClick；select 点开自绘选项列表、点选项回调 onSelectOptionClick（不执行 action 不写 infoMap）；text 只读
  - `FILTER_SETTINGS`（筛选设置区）：select 正常 onItemSelected→infoMap+action；text 可输入防抖 action；toggle/button 正常执行 action
- 保留 viewName js 动态显示名、style 应用、recycler 复用机制
- ExploreAdapter 改为调用 renderer，删除 ManageChecker 管理模式

## T3 ViewModel 重写
- 状态：tree（用户树）、presetTree（预填，不持久化）、displayTree=edited?tree:presetTree、currentL1/L2/L3
- LiveData：treeData、l2Data、l3Data、page/books 等沿用
- 节点执行 executeNode(node)：先 applyFilterSettings()（面板顺序遍历非 url 控件，infoMap 赋值+执行 action），再按类型：
  - url → exploreUrl=node.url 拉书
  - select → 写 infoMap[selectTitle]=option、执行 action，找该 select 段内默认 url（优先 title 含"全部/所有"，其次第一个 url 项），无则仅刷新面板不拉书
  - button → 仅执行 action，提示完成；reUiView 时刷新面板
- 失效校验 findKindForNode：url 比对 url 串、select 比对控件 title+chars 含 option、button 比对 title；失配 toast「该分类已失效，请重新添加」
- 删除旧 fullRow 运行时逻辑：bigTree/displayBigKinds/subBarKinds/bigCandidates/applyManageSelection/selectBig/selectSub/matchIntentUrl(改造为树定位)

## T4 Activity 与筛选页
- 列表页：第三栏 chip（布局 hsvThirdCategory），点击行为按设计文档七（再点高亮项无操作、切换联动重置下级栏）
- 菜单「传统发现」更名「筛选」（menu_category title）
- 筛选页（侧边 drawer 重构为 ScrollView 三区块）：
  - 已选分类三栏（chip 末尾「＋」弹提示；长按节点删除连带子级；同名不同引用显示"名称（序号）"、长按查看完整引用）
  - 操作按钮：清空重建（置 edited=false 重新预填）/ 清空（树置空+edited=true）
  - 筛选设置区（FILTER_SETTINGS renderer）改动即 infoMap.saveNow()
  - 传统发现面板（PANEL_ADD renderer）
- 添加菜单：PopupMenu 动态项（添加一级/二级/三级/取消），辖域过滤+跳级禁用：
  - 一级候选：任意可选项（url/select选项/button）
  - 二级候选：父级一级的辖域（分段标题→段内项；select选项→同 select 值域；url→面板全量；button/text→无）
  - 三级候选：跟随其二级节点的辖域（同规则递归）
- 添加成功置 edited=true、刷新三栏、按新树定位拉书

## T5 资源
- activity_explore_show.xml 加第三栏；筛选页布局重构（ScrollView + 三区块）
- menu/explore_show.xml 更名；strings.xml 新增筛选相关文案

## T6 验证与提交
- `./gradlew :app:compileAppDebugKotlin` 编译通过
- 提交推送 main（触发 Build ReadingD APK）
