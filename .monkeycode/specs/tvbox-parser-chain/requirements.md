# TVBox 解析链改进需求

## Introduction

阅读D需要稳定处理 TVBox/FongMi Config、CMS 站点和 FongMi Spider 返回的数据，使站点配置、详情线路和播放结果在统一 `VideoSource` 链路中保持协议语义。

## Glossary

- **TVBox Config**: 包含 `sites` 数组或单站点字段的 JSON 配置。
- **CMS 站点**: 通过苹果 CMS V10 API 提供分类、搜索、详情和播放数据的站点。
- **Spider 站点**: 通过 Jar/FongMi Spider 提供影视数据的站点。
- **播放结果**: 包含播放 URL、请求头和 `parse` 标志的数据。

## Requirements

### Requirement 1

**User Story:** 作为用户，我希望导入 TVBox 订阅后保留站点协议配置，以便每个站点按照原始类型工作。

#### Acceptance Criteria

1. WHEN 用户导入包含 `sites` 的 TVBox Config，系统 SHALL 保存完整原始配置并保留根级 `spider`、站点 `type`、`jar`、`header`、`ext` 和 API 字段。
2. WHEN 系统展开 TVBox Config，系统 SHALL 根据站点 API 形态和协议字段选择 CMS 或 Spider 解析路径。

### Requirement 2

**User Story:** 作为用户，我希望详情页完整显示多线路和多集播放项，以便选择正确的播放地址。

#### Acceptance Criteria

1. WHEN 详情返回 `vod_play_from` 与 `vod_play_url`，系统 SHALL 按线路和集数顺序生成播放项。
2. WHEN 详情返回 `play_lines` 或 `play_urls`，系统 SHALL 将直接线路数组转换为统一播放项。
3. WHEN 线路字段数量不匹配，系统 SHALL 保留存在完整 URL 的线路项。

### Requirement 3

**User Story:** 作为用户，我希望 Spider 返回的播放地址能够携带协议字段，以便直链和网页播放页得到正确处理。

#### Acceptance Criteria

1. WHEN 播放结果使用顶层或嵌套 `playUrl` 字段，系统 SHALL 提取 URL、`header`、`headers` 和 `headerInfo` 字段。
2. WHEN 播放结果包含 `parse` 字段，系统 SHALL 将其转换为统一播放结果的整数标志。
3. WHEN 请求头使用 JSON 对象、JSON 字符串或 `k=v&k2=v2` 格式，系统 SHALL 解析为统一请求头映射。

### Requirement 4

**User Story:** 作为用户，我希望 TVBox 接口错误能够被识别，以便看到明确的源错误信息。

#### Acceptance Criteria

1. IF TVBox 接口无响应，系统 SHALL 返回包含接口类型和请求地址的错误信息。
2. IF Spider 播放结果缺少 URL，系统 SHALL 使用原始播放值作为回退候选并保留错误上下文。
