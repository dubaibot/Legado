# Book Search Page Requirements

Scope: the standalone book-source SearchActivity. RSS and independent video-source search retain their existing behavior.

1. WHEN the bookshelf search entry is activated, the application SHALL open the standalone book search page.
2. The page SHALL preserve the order search bar, source groups, history, hot rankings, result status and results; the search bar remains visible during scrolling.
3. The search bar SHALL provide back, a rounded filled keyword field with search icon, clear action and integrated submit button, followed by the overflow menu. The source-range header SHALL provide the exact-title checkbox.
4. The overflow menu SHALL provide source management, recommendation settings, view settings and search logs in that order. The source-range header SHALL provide exact search and group multiselect with trailing checkboxes, plus an advanced-filter dialog action.
5. WHEN a keyword contains a title and trailing space-separated author, search SHALL request the title and independently filter by the normalized author. Exact mode SHALL require equality of the complete title.
6. Groups SHALL occupy one horizontally scrollable row, begin with Comprehensive, support persisted multiple selection through a labeled checkbox and merge enabled-source results with deduplication.
7. History SHALL remain present in every search state, retain the latest 20 keywords and support immediate search, individual deletion and clearing.
8. Hot-source settings SHALL allow ordered selections of openable discovery addresses across enabled sources with enabledExplore, support expand/collapse, confirm/cancel and persist choices. Initial defaults SHALL include all discovery fragments of the installed Guduo source.
9. Hot requests SHALL use independent address timeouts, preserve successful partial results, log errors, cache each source/address for 30 minutes and silently refresh cached data. Manual refresh SHALL fetch every selected address.
10. Hot cards SHALL appear in three columns with cover, rank, title, kind/author and source/category attribution. Cards SHALL preserve source item order, deduplicate titles and limit output to 20.
11. WHEN no address is selected or search results are present, the hot section SHALL occupy zero space. Active loading with empty cards SHALL show a title and skeleton state. An empty selected ranking SHALL retain the address switcher and an empty-state message.
12. WHEN a hot card is clicked, the application SHALL populate its title and submit using the current groups and exact setting.
13. Submitted results SHALL show the filtered count, persisted list/grid choice and column count, pull refresh, paging and a book-details action. Empty results SHALL retain history and any available hot section.
14. Changes to groups, exact mode or advanced filters SHALL reload results. Filters SHALL include missing covers, completion state and minimum word count, with persisted preferences.
15. WHEN a source is disabled or discovery is disabled, its selections SHALL become invalid and its hot cards SHALL be removed.
16. The hot section SHALL provide a horizontally scrollable address switcher containing each configured discovery address, with the first available address selected initially. WHEN an address is selected, the hot section SHALL display that address using cache-first loading and silent refresh. The switcher SHALL preserve the source group and exact-title search settings.
17. The hot-source dialog SHALL filter sources by name or address as the user types and preserve draft selections across filter changes. The dialog SHALL provide query clearing and a no-match state.
18. The main search field SHALL use a compact approximately 38dp height with its integrated submit button.
19. The group row SHALL provide a fixed trailing chevron opening a scrollable group-selection dialog using the current single/multiple selection preference.
20. The filter header top inset SHALL be 3dp. The gap between the filter header and group row SHALL be 4dp. The gap between the group row and history SHALL be 4dp. Recommendation heading-to-address and address-to-card gaps SHALL be 10dp. The recommendation heading SHALL read Recommendation with no subtitle.
21. The result status row SHALL provide a recommendation toggle before the layout toggle. Toggling SHALL preserve collected search results and suspend result paging while explicitly viewing recommendations.
22. Recommendation settings SHALL place selected sources and discovery addresses first and render their text blue. Query filtering SHALL preserve selections and selected-first ordering.
23. History tags SHALL support tap-to-search and long-press deletion, with a subtle shadow shared by group and discovery-address tags.
24. WHILE searching, the result-count row SHALL show a clickable progress indicator immediately after its text. Clicking the indicator SHALL stop searching while preserving collected results.
25. Result-grid titles SHALL reserve two lines. Recommendation cards SHALL display rank badges for positions one through three.

26. Group, history and recommendation-address tags SHALL use compact padding while retaining subtle shadows and their existing click and long-press actions.
27. Recommendation settings SHALL support long-press drag sorting of sources and discovery addresses within their selected-first partitions. Confirmation SHALL persist source and address order, including unselected addresses; cancellation SHALL discard changes. Selected-address order SHALL determine the recommendation address switcher order.
