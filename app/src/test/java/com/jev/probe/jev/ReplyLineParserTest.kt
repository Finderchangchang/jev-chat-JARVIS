package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyLineParserTest {
    @Test
    fun preservesLeadingNumbers() {
        assertEquals(
            listOf("2点见", "30分钟后到", "123号门口等你"),
            ReplyLineParser.parse("2点见\n30分钟后到\n123号门口等你")
        )
    }

    @Test
    fun removesNumberedPrefixWithoutEatingReplyText() {
        assertEquals(
            listOf("2点见", "30分钟后到", "123号门口等你"),
            ReplyLineParser.parse("1. 2点见\n2. 30分钟后到\n3. 123号门口等你")
        )
    }

    @Test
    fun preservesDecimalNumbers() {
        assertEquals(
            listOf("1.5小时后到", "2.0版本已经更新", "3.14不是3.15"),
            ReplyLineParser.parse("1.5小时后到\n2.0版本已经更新\n3.14不是3.15")
        )
    }

    @Test
    fun preservesNegativeNumbers() {
        assertEquals(
            listOf("-2度，记得加衣服", "-3.5这个数没算错", "-10也可以"),
            ReplyLineParser.parse("-2度，记得加衣服\n-3.5这个数没算错\n-10也可以")
        )
    }

    @Test
    fun removesBulletPrefixes() {
        assertEquals(
            listOf("2点见", "30分钟后到", "1.5小时后到"),
            ReplyLineParser.parse("- 2点见\n* 30分钟后到\n- 1.5小时后到")
        )
    }

    @Test
    fun removesNumberPrefixesWithClosingParenthesis() {
        assertEquals(
            listOf("2点见", "30分钟后到", "123号门口等你"),
            ReplyLineParser.parse("1) 2点见\n2) 30分钟后到\n3) 123号门口等你")
        )
    }

    @Test
    fun keepsAmbiguousPrefixes() {
        assertEquals(
            listOf("1.明天见", "*这句话先保留", "2026. 9月再说"),
            ReplyLineParser.parse("1.明天见\n*这句话先保留\n2026. 9月再说")
        )
    }

    @Test
    fun removesOnlyOnePrefix() {
        assertEquals(
            listOf("2. 先确认时间", "* 这部分是正文", "- 这部分也是正文"),
            ReplyLineParser.parse("1. 2. 先确认时间\n- * 这部分是正文\n* - 这部分也是正文")
        )
    }

    @Test
    fun handlesWhitespaceAndCrLf() {
        assertEquals(
            listOf("2点见", "30分钟后到", "好的"),
            ReplyLineParser.parse("  1.\t2点见  \r\n\t*\t30分钟后到\r\n 好的 \r\n")
        )
    }

    @Test
    fun skipsBlankLinesAndEmptyListItems() {
        assertEquals(
            listOf("收到", "好", "明天见"),
            ReplyLineParser.parse("\n  \n1. \n- \n*\n2) \n收到\n\n好\n明天见\n")
        )
    }

    @Test
    fun padsMissingReplies() {
        assertEquals(
            listOf("2点见", "（稍等，我看下）", "（稍等，我看下）"),
            ReplyLineParser.parse("2点见")
        )
    }

    @Test
    fun keepsOnlyTheFirstThreeReplies() {
        assertEquals(listOf("收到", "好", "明天见"), ReplyLineParser.parse("收到\n好\n明天见\n不保留这条"))
    }

    @Test
    fun keepsExistingEmptyInputFallback() {
        assertEquals(List(3) { "（稍等，我看下）" }, ReplyLineParser.parse(" \n\t\n"))
    }

    @Test
    fun keepsOpeningQuoteCleanupWithoutStrippingNumbers() {
        assertEquals(
            listOf("2点见", "30分钟后到", "123号门口等你"),
            ReplyLineParser.parse("\"2点见\n1. \"30分钟后到\n\"2. 123号门口等你")
        )
    }
}
