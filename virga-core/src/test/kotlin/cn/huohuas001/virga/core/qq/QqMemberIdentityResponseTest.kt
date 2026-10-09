package cn.huohuas001.virga.core.qq

import com.alibaba.fastjson.JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqMemberIdentityResponseTest {
    @Test fun `both actual permission error formats are recognized`() {
        assertTrue(parseQqMemberIdentityResponse(400, JSON.parseObject("""{"code":11253}"""), "member").permissionDenied)
        assertTrue(parseQqMemberIdentityResponse(400, JSON.parseObject("""{"err_code":40012010}"""), "member").permissionDenied)
    }
    @Test fun `transient errors remain failures and cannot establish an identity`() {
        assertFailsWith<IllegalStateException> {
            parseQqMemberIdentityResponse(500, JSON.parseObject("""{"err_code":11252}"""), "member")
        }
    }
    @Test fun `success validates requested member and retains optional union`() {
        assertEquals("union", parseQqMemberIdentityResponse(200,
            JSON.parseObject("""{"member_openid":"member","union_openid":"union"}"""), "member").unionOpenId)
        assertFailsWith<IllegalStateException> {
            parseQqMemberIdentityResponse(200, JSON.parseObject("""{"member_openid":"other","union_openid":"union"}"""), "member")
        }
    }
}
