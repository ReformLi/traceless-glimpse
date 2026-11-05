package com.hpu.browseguard.data.output

import com.hpu.browseguard.data.history.RecordLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 哪几种「搬动」真的存在——界面拿 null 决定按钮是否可点，搬运工拿它选分支。 */
class MoveDirectionTest {

    private val tree = MoveTarget.PublicTree("content://tree/document/1")

    @Test
    fun theThreeRealDirections() {
        assertEquals(MoveDirection.PRIVATE_TO_PUBLIC, moveDirection(RecordLocation.PRIVATE, tree))
        assertEquals(MoveDirection.PUBLIC_TO_PUBLIC, moveDirection(RecordLocation.PUBLIC, tree))
        assertEquals(
            "反向操作也在内：公开目录的东西能收进应用专属目录",
            MoveDirection.PUBLIC_TO_PRIVATE,
            moveDirection(RecordLocation.PUBLIC, MoveTarget.PrivateSandbox),
        )
    }

    @Test
    fun nonMovesAreRefusedBeforeAnyCopyHappens() {
        assertNull(
            "私有目录内部移动等于原地复制一遍，白占空间",
            moveDirection(RecordLocation.PRIVATE, MoveTarget.PrivateSandbox),
        )
        assertNull("电脑上的文件不在手机上，搬不动", moveDirection(RecordLocation.ENGINE, tree))
        assertNull("还没有产物", moveDirection(RecordLocation.MISSING, MoveTarget.PrivateSandbox))
    }
}
