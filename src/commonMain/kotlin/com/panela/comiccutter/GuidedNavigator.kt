package com.panela.comiccutter

/** Position in the guided reading flow: [page] = page index, [unit] = navigation unit (panel index, or 0 = full page). */
data class GuidedPosition(val page: Int, val unit: Int)

/**
 * Pure index logic for panel-by-panel navigation across page boundaries.
 * [unitsAt] returns the number of navigation units on a page (always >= 1;
 * a page with fewer than 2 detected panels has exactly 1 unit = full page).
 */
object GuidedNavigator {

    fun next(pos: GuidedPosition, pageCount: Int, unitsAt: (Int) -> Int): GuidedPosition? {
        if (pos.unit + 1 < unitsAt(pos.page)) return GuidedPosition(pos.page, pos.unit + 1)
        val nextPage = pos.page + 1
        if (nextPage >= pageCount) return null
        return GuidedPosition(nextPage, 0)
    }

    fun previous(pos: GuidedPosition, pageCount: Int, unitsAt: (Int) -> Int): GuidedPosition? {
        if (pos.unit > 0) return GuidedPosition(pos.page, pos.unit - 1)
        val prevPage = pos.page - 1
        if (prevPage < 0) return null
        return GuidedPosition(prevPage, unitsAt(prevPage) - 1)
    }
}
