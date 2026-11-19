# Unified Navigation and Source Management Requirements

Updated: 2026-11-19

## Introduction

将底部导航栏收敛为四个 Tab：书架、发现、短剧、我的。发现 Tab 合并书源、订阅源、首页三块内容，采用首页源式顶栏。源管理统一为一个 Activity，顶部类型栏切换书源/订阅源/py/TVbox，第二栏分组标签栏管理全部源。短剧 Tab 暂为空白占位。

## Glossary

- **发现页**: 合并书源、订阅源、首页的 Tab 页面。
- **源管理页**: 统一管理书源、订阅源、py 源、TVbox 源的 Activity。
- **首页源**: py/json/txt/html 类型的首页内容源。
- **分类页**: 书源的分类浏览页面，对应现有 ExploreShowActivity。

## Requirements

### Requirement 1: 四 Tab 底部导航

**User Story:** AS a user, I want the bottom navigation to show 书架、发现、短剧、我的 four tabs, so that I can access core areas in fewer taps.

#### Acceptance Criteria

1. WHEN the main screen launches, the bottom navigation SHALL display exactly four tabs in the order: 书架、发现、短剧、我的.
2. WHEN a tab is selected, the system SHALL switch the ViewPager to the corresponding page.
3. WHEN the user reselects the 书架 tab, the system SHALL scroll the bookshelf to the top.
4. WHEN the user reselects the 发现 tab, the system SHALL scroll the discover content to the top.
5. WHEN the user reselects the 短剧 tab, the system SHALL show the placeholder page.

### Requirement 2: 发现页顶栏

**User Story:** AS a user, I want the discover page top bar to show the current source name and switcher, login button, search, history, and settings, so that I can quickly switch sources and access common actions.

#### Acceptance Criteria

1. WHEN the discover page is shown, the top bar SHALL display the source name, login (when available), search, viewing history, and settings in that order. Names longer than ten characters SHALL display their first ten characters followed by `...`.
2. WHEN the source name is tapped, the system SHALL open a centered picker with a compact search field, single-line horizontally scrollable type and group bars, and a two-column source list. The type bar SHALL contain 书源、订阅源、py源、TVbox源. Each type SHALL retain its own group filter while the picker is open, and the current source SHALL appear first.
3. WHEN the selected source supports login, the system SHALL show the login button on the top bar.
4. IF the selected source does not support login, the system SHALL hide the login button.
5. WHEN the user clicks the search button, the system SHALL launch the standalone SearchActivity.
6. WHEN the user clicks the history button, the system SHALL open the viewing history.
7. WHEN the user clicks settings, the system SHALL offer source management, local PY import, network import for book or RSS sources, TVbox interface import, and danmaku API configuration. The system SHALL offer source editing only for book and RSS sources.
8. WHEN a user selects a source, the system SHALL persist its type-qualified key and restore it after returning to the app; if that source is unavailable, the system SHALL select and persist the first available source.
9. WHEN source login is available, the top bar SHALL use the same person icon as the bottom navigation's 我的 item.
10. WHEN the source picker is displayed, the source name and switch control SHALL be adjacent, its search text SHALL remain vertically readable, and the 书源 group list SHALL include 全部.
11. WHEN the user opens viewing history from Discover, the system SHALL show book and RSS reading records from all sources.

### Requirement 3: 发现页内容

**User Story:** AS a user, I want to switch between sources and browse each source's content inside the discover tab, so that I can explore without navigating away from the main tab.

#### Acceptance Criteria

1. WHEN the user selects a source, the system SHALL show its category selector above its content when categories exist.
2. WHEN the user selects a book-source category, the system SHALL display the category's books and support opening book details or video playback.
3. WHEN the user selects an RSS category, the system SHALL display its articles and route article taps according to the current RSS source type.
4. WHEN the selected RSS source defines `startHtml`, the system SHALL render that homepage inline within the discover tab.
5. WHEN a homepage source is selected, the system SHALL display its homepage content with category tags and video grid.
6. WHEN paginated content is displayed, the system SHALL load more content near the end and allow retry after a request failure.
7. WHEN book or RSS category contents are displayed, the system SHALL display a three-column cover grid with the video grid spacing. Book names and RSS article titles SHALL appear below their covers. Book latest-chapter text and RSS publication time SHALL appear in white at the upper right over a top gradient; book-author text SHALL appear in white at the lower right over a bottom gradient.
8. IF a book, RSS list, or startHtml request fails, the discover page SHALL omit technical error details and provide a retry action.
9. WHILE an RSS source with startHtml is displayed inline, the system SHALL hide the main bottom navigation and restore it when the user switches to another source or leaves the discover page.
10. WHEN an RSS source has a next-page rule or a page-placeholder URL, the system SHALL request subsequent pages with an incremented page value, including feeds parsed by the default XML parser.
10.1 WHEN the user scrolls an RSS category list near its end and a next page is available, the system SHALL load and append that page's articles after the currently displayed content. This SHALL repeat for all subsequent pages until no new articles are returned; a single page of pagination SHALL NOT terminate further pagination.
10.2 WHEN an RSS pagination load completes, the system SHALL append the newly loaded articles after the current list in a single state update and SHALL NOT reset or jump the list's scroll position. The pagination experience SHALL match the book-source pagination behavior.
10.3 IF an RSS pagination request returns only articles already displayed, the system SHALL stop pagination for that category instead of retrying the same page.
11. WHEN the user switches categories within a source's discover content, the system SHALL restore the last browsed scroll position of the newly selected category and record the position of each category while browsing.
12. WHEN the user switches to another source, the system SHALL invalidate all recorded category positions so the new source starts browsing from the top.
13. WHEN the user switches away from the discover tab to another tab, the system SHALL invalidate all recorded category positions; returning to the discover tab starts browsing from the top.

### Requirement 4: 统一源管理页

**User Story:** AS a user, I want a unified source management page with a top type bar and a group tag bar, so that I can manage all source types and groups in one place.

#### Acceptance Criteria

1. WHEN the SourceManageActivity launches, the system SHALL display a top type bar with four items: 书源、订阅源、py、TVbox.
2. WHEN the user selects a type in the type bar, the system SHALL show the sources of that type.
3. WHEN the user selects a group in the group tag bar, the system SHALL filter the current type's sources by that group.
4. WHEN the user switches source type, the system SHALL preserve that type's selected group filter.
5. WHEN the user requests group assignment for a source, the system SHALL allow selecting an existing group, creating a group, or moving the source to ungrouped.
6. WHEN the user taps a source, the system SHALL open the corresponding edit or detail page.
7. WHEN a homepage source is managed under TVbox, the system SHALL include imported WEB homepage sources.
8. WHEN source management is shown, the system SHALL use the book-management toolbar and horizontally scrollable type and group bars. Book and RSS rows SHALL show a checkbox, source name, enabled switch, edit action, and a menu for moving the source to the top, moving it to the bottom, or deleting it. PY and TVBOX rows SHALL show a checkbox, source name, enabled switch, delete action, and a menu for moving the source to the top or bottom. Rows SHALL omit source URLs and inline group assignment controls.
9. WHEN the user long-presses and drags anywhere on a source row, the system SHALL persist its new position within that source type without displaying a dedicated drag handle.
10. WHEN the user taps select-all while all visible rows are selected, the system SHALL clear the visible selection.
11. WHEN a TVbox site is deleted, the system SHALL remove that site from the available catalog and retain its parent configuration and sibling sites.
12. WHEN unified source management is shown, its list-row spacing and action affordances SHALL follow the existing 我的 > 书源管理 screen, with type and group bars added above the list.

### Requirement 5: 短剧占位页

**User Story:** AS a user, I want a short drama tab placeholder, so that the entry is reserved for future development.

#### Acceptance Criteria

1. WHEN the user selects the 短剧 tab, the system SHALL display a blank placeholder page.
2. WHEN the placeholder page is shown, the system SHALL display a "功能开发中" message.

### Requirement 6: 导航配置迁移

**User Story:** AS a developer, I want the navigation configuration to reflect the new four-tab structure, so that existing preferences are migrated correctly.

#### Acceptance Criteria

1. WHEN the app first launches with the new structure, the system SHALL set the default nav order to bookshelf, explore, drama, my.
2. WHEN the user has existing navigation preferences, the system SHALL migrate or ignore stale homepage/rss entries.

### Requirement 7: 四类型搜索

1. WHEN the search page is shown, the system SHALL allow searching enabled book, RSS, py, and TVbox sources with a single-source or all-enabled-sources scope.
2. WHEN a search result is tapped, the system SHALL open the corresponding book, RSS, or video destination using the result's source.
3. WHEN a search is canceled or replaced, the system SHALL discard updates from its previous request.
