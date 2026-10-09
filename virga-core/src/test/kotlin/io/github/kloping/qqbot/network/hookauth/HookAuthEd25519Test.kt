package io.github.kloping.qqbot.network.hookauth

import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Webhook signing uses the JDK's Ed25519; RFC 8032 section 7.1 vectors pin the exact output. */
class HookAuthEd25519Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val vectors = listOf(
        Triple(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "" to "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        ),
        Triple(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72" to "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
    )

    @Test
    fun `seeded key pair and signature match RFC 8032`() {
        for ((seed, publicKey, case) in vectors) {
            val (message, signature) = case
            val pair = HookAuth.generateEd25519KeyPair(HookAuth.hexDecode(seed))
            assertEquals(publicKey, hex(pair.public.encoded).takeLast(64))
            assertEquals(signature, hex(HookAuth.signMessage(pair.private, HookAuth.hexDecode(message))))
        }
    }

    @Test
    fun `raw and encoded public keys both verify and reject tampering`() {
        val (seed, publicKey, case) = vectors[1]
        val (message, signature) = case
        val encoded = HookAuth.generateEd25519KeyPair(HookAuth.hexDecode(seed)).public.encoded
        for (key in listOf(HookAuth.hexDecode(publicKey), encoded)) {
            fun verify(data: String) = Signature.getInstance("Ed25519").run {
                initVerify(HookAuth.ed25519PublicKey(key))
                update(HookAuth.hexDecode(data))
                verify(HookAuth.hexDecode(signature))
            }
            assertTrue(verify(message))
            assertFalse(verify("73"))
        }
    }

    @Test
    fun `invalid seed and hex are rejected`() {
        assertFailsWith<IllegalArgumentException> { HookAuth.generateEd25519KeyPair(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { HookAuth.hexDecode("abc") }
        assertFailsWith<IllegalArgumentException> { HookAuth.hexDecode("zz") }
    }
}
