# Python Source Categories And Routes

1. WHEN a Python Spider provides home categories and recommendations, the application SHALL display both supported result sets.
2. WHEN a Spider provides category filters, the application SHALL display a horizontal subcategory row for each filter group below the main category bar.
3. WHEN a category changes, the application SHALL select the first option in each filter group and request page one with those values.
4. WHEN a filter changes, the application SHALL reset pagination and include the selected values in subsequent category requests and cache keys.
5. WHEN an earlier category request completes after a selection change, the application SHALL retain the current selection results.
6. WHEN a detail response contains CMS groups or nested episode arrays, the application SHALL preserve episode URLs and their original route flags.
7. WHEN an episode is played, the application SHALL pass the original route flag to playerContent and retain playback headers and parse mode.

Validation covers JSON serialization through the Python bridge, empty filter values, optional home methods, empty route groups, missing route names, nested episodes and dollar characters in URLs. Device validation uses actual user sources after the offline regression checks.
