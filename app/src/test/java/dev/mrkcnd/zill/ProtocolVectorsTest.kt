package dev.mrkcnd.zill

import dev.mrkcnd.zill.protocol.Base64Url
import dev.mrkcnd.zill.protocol.MacId
import dev.mrkcnd.zill.protocol.PairLink
import dev.mrkcnd.zill.protocol.PairingCode
import dev.mrkcnd.zill.protocol.PairingProof
import dev.mrkcnd.zill.protocol.RecognitionTag
import dev.mrkcnd.zill.protocol.hex
import dev.mrkcnd.zill.protocol.hmac
import dev.mrkcnd.zill.protocol.sha256
import dev.mrkcnd.zill.protocol.viewportJson
import dev.mrkcnd.zill.protocol.AudioMessage
import dev.mrkcnd.zill.protocol.CursorShape
import dev.mrkcnd.zill.protocol.Esds
import dev.mrkcnd.zill.protocol.helloJson
import dev.mrkcnd.zill.protocol.parseMacPointer
import dev.mrkcnd.zill.protocol.MacMenu
import dev.mrkcnd.zill.protocol.HostSettingsState
import dev.mrkcnd.zill.protocol.SettingsChange
import dev.mrkcnd.zill.protocol.SettingsChoices
import dev.mrkcnd.zill.protocol.fetchMenuJson
import dev.mrkcnd.zill.protocol.menuRows
import dev.mrkcnd.zill.protocol.pressMenuItemJson
import dev.mrkcnd.zill.video.HevcDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vectors in Sill's docs/remote-access-plan.md (computed there in Python; Swift must match, and so must we). */
class ProtocolVectorsTest {
    private val fpMac = hex("348a629f5ceed032c3e8706ec47d9bfafb00fb4250b018dd965435ca50cb836e")
    private val fpDevice = hex("263a4dbe41488fb87214b0032339dbb9f0c8da14c16dfcf13084bf3c2552eca5")

    @Test fun stand_in_fingerprints_are_sha256() {
        assertArrayEquals(fpMac, sha256("mac".toByteArray()))
        assertArrayEquals(fpDevice, sha256("device".toByteArray()))
    }

    @Test fun mac_id() {
        assertEquals("A3C5HR4RBV67YR21", MacId.make(sha256("example".toByteArray())))
    }

    @Test fun code_key_and_proofs() {
        val k = PairingProof.codeKey("482913557208", fpMac)
        assertArrayEquals(hex("e9baf879a1cc4ac8b21554df480b6c1621671319107c91e7cb05d154f649d31d"), k)
        assertEquals("dk7PZj4vHsAxj--1u9Vg7ehSn8a1tWc0RCG46YpFVO8", Base64Url.encode(PairingProof.deviceProof(k, fpDevice, fpMac)))
        assertEquals("6BmGWC9XT8J8OY8phhq9bASgoSnoWpRxBeqZtvRrWmk", Base64Url.encode(PairingProof.macProof(k, fpMac, fpDevice)))
    }

    @Test fun qr_proofs() {
        val secret = ByteArray(16) { 0x22 }
        assertEquals("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo", Base64Url.encode(PairingProof.deviceProof(secret, fpDevice, fpMac)))
        assertEquals("XAX-uZPsq_Ax8fvI_sV7Ex0a-ROi19rI-FW-ykPh9TE", Base64Url.encode(PairingProof.macProof(secret, fpMac, fpDevice)))
    }

    @Test fun recognition_tag() {
        val key = ByteArray(32) { 0x11 }
        assertTrue(RecognitionTag.matches("oaKjpKWmL4V47Ctm", key))
        assertFalse(RecognitionTag.matches("oaKjpKWmL4V47Ctn", key))
        assertFalse(RecognitionTag.matches("oaKjpKWmL4V47Ctm", ByteArray(32) { 0x12 }))
        // and it is what we would compute ourselves
        val nonce = hex("a1a2a3a4a5a6")
        val tag = nonce + hmac(key, "sill-tag-v1".toByteArray() + nonce).copyOfRange(0, 6)
        assertEquals("oaKjpKWmL4V47Ctm", Base64Url.encode(tag))
    }

    @Test fun damm_check_digit() {
        assertTrue(PairingCode.check("4829 1355 7208") is PairingCode.Check.Ok)
        assertTrue(PairingCode.check("482913557209") is PairingCode.Check.Typo)
        assertTrue(PairingCode.check("48291355720") is PairingCode.Check.Length)
        // every single-digit substitution is caught
        val good = "482913557208"
        for (i in good.indices) for (d in '0'..'9') if (d != good[i]) {
            val bad = good.substring(0, i) + d + good.substring(i + 1)
            assertTrue(bad, PairingCode.check(bad) is PairingCode.Check.Typo)
        }
    }

    @Test fun pair_link() {
        val fp = sha256("example".toByteArray())
        val url = "sill://pair?v=1&m=${MacId.make(fp)}&k=${Base64Url.encode(fp)}&s=${Base64Url.encode(ByteArray(16) { 0x22 })}" +
            "&n=Mac%20mini&p=7455&a=100.101.102.103&a=%5Bfd7a%3A115c%3A%3A1%5D%3A7456"
        val link = PairLink.parse(url)
        assertNotNull(link)
        assertEquals("Mac mini", link!!.name)
        assertEquals(7455, link.port)
        assertEquals(listOf("100.101.102.103", "[fd7a:115c::1]:7456"), link.addresses)
        // m must be the Mac ID of k
        assertEquals(null, PairLink.parse(url.replace("m=${MacId.make(fp)}", "m=0000000000000000")))
    }

    @Test fun base64url_is_strict() {
        assertEquals(null, Base64Url.decode("ab+c"))
        assertEquals(null, Base64Url.decode("abcde"))
        assertArrayEquals(byteArrayOf(-1), Base64Url.decode("_w"))
    }

    @Test fun length_prefixed_to_annex_b() {
        val au = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 1, 0x40)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0, 0, 1, 0x40), HevcDecoder.toAnnexB(au, 4))
        assertEquals(null, HevcDecoder.toAnnexB(byteArrayOf(0, 0, 0, 9, 1), 4))
    }

    @Test fun sps_size_of_the_test_clip() {
        val clip = StandInServe().parseClip(javaClass.classLoader!!.getResourceAsStream("testclip.hevc").readBytes())
        val ps = dev.mrkcnd.zill.protocol.ParameterSets.decode(clip.first)!!
        assertEquals(3, ps.sets.size)
        val sps = ps.sets.first { ((it[0].toInt() shr 1) and 0x3F) == 33 }
        assertEquals(1280 to 800, dev.mrkcnd.zill.video.SpsParser.size(sps))
        assertTrue(clip.second.first().second) // the clip starts on a keyframe
    }

    /** Kind 9's scale is Aa's text scale (Viewport.swift), never the screen density; held to 0.25…4. */
    @Test fun viewport_scale_is_the_text_scale() {
        val off = viewportJson(411.4, 814.5, null, 60)
        assertFalse(off.has("scale"))
        assertEquals(411.4, off.getDouble("width"), 0.0)
        assertEquals(60, off.getInt("fps"))
        assertEquals(1.25, viewportJson(411.4, 814.5, 1.25, 60).getDouble("scale"), 0.0)
        assertEquals(4.0, viewportJson(411.4, 814.5, 10.0, null).getDouble("scale"), 0.0)
        assertFalse(viewportJson(411.4, 814.5, Double.NaN, null).has("scale"))
        assertEquals(16384.0, viewportJson(1e300, 1.0, null, null).getDouble("width"), 0.0)
    }

    /** AudioToolbox's AAC-ELD magic cookie (tools/eld-clip.swift) is an ES descriptor; csd-0 is the ASC inside it. */
    @Test fun aac_eld_cookie_to_audio_specific_config() {
        val cookie = hex("0380808024000000048080801640140018000001f4000001f4000580808004f8e65000068080800102")
        assertEquals("f8e65000", Esds.audioSpecificConfig(cookie)!!.hex())
        assertEquals("f8e65000", Esds.audioSpecificConfig(hex("f8e65000"))!!.hex())   // a bare ASC
        assertEquals(null, Esds.audioSpecificConfig(hex("0380")))                     // cut short
    }

    /** Kind 43 as Audio.swift lays it out: type 2 · epoch u16 · seq u32 · flags · count · (u16 len · bytes)… */
    @Test fun audio_messages() {
        val packets = hex("02" + "0007" + "0000002a" + "01" + "02" + "0002" + "aabb" + "0001" + "cc")
        val p = (AudioMessage.parse(packets) as AudioMessage.Packets).packets
        assertEquals(7, p.epoch); assertEquals(42L, p.seq); assertTrue(p.segmentStart)
        assertEquals(listOf("aabb", "cc"), p.packets.map { it.hex() })
        assertEquals(null, AudioMessage.parse(packets + byteArrayOf(0)))               // bytes left over
        assertEquals(null, AudioMessage.parse(packets.copyOf(packets.size - 1)))       // cut short
        assertEquals(null, AudioMessage.parse(hex("09")))                              // unknown type
        val json = """{"codec":"aac-eld","sampleRate":48000,"channels":2,"framesPerPacket":480,"primingFrames":240,"epoch":7,"cookie":"+OZQAA==","app":"Music"}"""
        val f = (AudioMessage.parse(byteArrayOf(1) + json.toByteArray()) as AudioMessage.Format).format
        assertEquals("aac-eld", f.codec); assertEquals(240, f.primingFrames); assertEquals(7, f.epoch)
        assertEquals("f8e65000", f.cookie.hex()); assertEquals("Music", f.app)
        assertEquals("aac-eld", helloJson("1", "1", "x").getJSONArray("audio").getString(0))
    }

    @Test fun cursor_shape_and_mac_pointer() {
        val blob = java.nio.ByteBuffer.allocate(20).putFloat(4f).putFloat(2f).putFloat(17f).putFloat(23f).put(byteArrayOf(1, 2, 3, 4)).array()
        val c = CursorShape.parse(blob)!!
        assertEquals(4f, c.hotX); assertEquals(23f, c.height); assertEquals(4, c.png.size)
        assertEquals(null, CursorShape.parse(blob.copyOf(16)))
        val on = parseMacPointer("""{"inside":true,"seen":12,"x":0.4213,"y":0.1873}""".toByteArray())!!
        assertEquals(0.4213 to 0.1873, on.position); assertEquals(12L, on.seen)
        assertEquals(null, parseMacPointer("""{"inside":false,"seen":12}""".toByteArray())!!.position)
        assertEquals(null, parseMacPointer("{}".toByteArray())!!.position)
    }

    /** Kind 24's three shapes (MacMenu.swift), and the top level held to what Sill's MacMenuState keeps. */
    @Test fun mac_menu_messages() {
        val top = MacMenu.parse("""{"version":3,"app":"Safari","bundleID":"com.apple.Safari","answering":1,
            "menus":[{"id":"1","title":"Safari","submenu":true},{"id":"2","title":"File","submenu":true},
            {"id":"02","title":"Bad id"},{"id":"2","title":"Duplicate"},{"id":"3","title":""},{"id":"4.1","title":"Two parts"}]}""".toByteArray())!!
        assertTrue(top.isTopLevel); assertEquals(3, top.version); assertEquals("Safari", top.app)
        assertEquals(listOf("Safari", "File"), top.menus!!.map { it.title })
        val menu = MacMenu.parse("""{"version":3,"answering":7,"menu":"2","more":4,"items":[
            {"separator":true},{"id":"2.0","title":"New Window","key":"⌘N"},{"id":"2.1","title":"Recent","submenu":true},
            {"separator":true},{"separator":true},{"id":"2.4","title":"Show Tabs","mark":"✓","enabled":false},
            {"id":"2.5","title":"Mixed","mark":"-"},{"id":"2.6","title":""},{"separator":true}]}""".toByteArray())!!
        assertFalse(menu.isTopLevel); assertEquals(7, menu.answering); assertEquals(4, menu.more)
        val rows = menuRows(menu.items!!)
        assertEquals(listOf("New Window", "Recent", "", "Show Tabs", "Mixed"), rows.map { it.title })
        assertTrue(rows[2].separator); assertEquals("⌘N", rows[0].key); assertTrue(rows[1].submenu)
        assertFalse(rows[3].enabled); assertEquals("✓", rows[3].mark); assertTrue(rows[4].mixed)
        val refused = MacMenu.parse("""{"version":3,"answering":9,"pressed":false,"note":"The menus changed."}""".toByteArray())!!
        assertEquals(false, refused.pressed); assertEquals("The menus changed.", refused.note)
        // Kind 27 without an id subscribes; with one it names the menu by id and title in a version.
        assertEquals("""{"token":1}""", fetchMenuJson(1).toString())
        val f = fetchMenuJson(2, 3, "2", "File"); assertEquals("2", f.getString("id")); assertEquals(3, f.getInt("version")); assertEquals("File", f.getString("title"))
        val p = pressMenuItemJson(4, 3, "2.0", "New Window"); assertEquals("2.0", p.getString("id")); assertEquals(4, p.getInt("token"))
    }

    /** Kind 16 as HostSettings.swift encodes it, an older Mac's (no audio, no wireless) too; kind 17 holds only what changed. */
    @Test fun host_settings_messages() {
        val h = HostSettingsState.parse("""{"settings":{"maxFPS":120,"bitrate":15000000,"captureScale":2,"prioritizeSpeed":false,
            "virtualDisplay":true,"directWireless":false,"sendAudio":true},"persistent":true,"virtualDisplayAvailable":true,
            "softwareEncoder":false,"stream":{"width":2940,"height":1912,"fps":60,"mbps":15,"onVirtualDisplay":true},"answering":12,
            "away":{"homeBitrate":15000000,"homeCaptureScale":2,"awayBitrate":4000000,"awayCaptureScale":1,"thisConnectionAway":false,"awayRunning":false},
            "link":{"state":"behind","withheldPerSecond":9,"bitrate":15000000,"carriedKbps":6200,"suggestedBitrate":8000000},
            "audioNote":"Couldn’t capture the sound of Safari","sessionPaired":true}""".toByteArray())!!
        assertEquals(120, h.settings.maxFPS); assertEquals(2.0, h.settings.captureScale, 0.0); assertEquals(true, h.settings.sendAudio)
        assertEquals(12, h.answering); assertEquals(2940, h.stream!!.width); assertTrue(h.link!!.isBehind)
        assertEquals(8_000_000, h.link!!.suggestedBitrate); assertEquals(true, h.sessionPaired)
        val old = HostSettingsState.parse("""{"settings":{"maxFPS":60,"bitrate":12000000,"captureScale":1,"prioritizeSpeed":true,
            "virtualDisplay":false},"persistent":false,"virtualDisplayAvailable":false,"softwareEncoder":true}""".toByteArray())!!
        assertEquals(null, old.settings.sendAudio); assertEquals(null, old.settings.directWireless); assertEquals(null, old.sessionPaired)
        assertEquals("Custom — 12 Mbps", SettingsChoices.qualityTitle(old.settings.bitrate))
        assertEquals("Balanced — 15 Mbps", SettingsChoices.qualityTitle(15_000_000))
        val c = SettingsChange(maxFPS = 60)
        val j = c.json(13)
        assertEquals(setOf("token", "maxFPS"), j.keys().asSequence().toSet()); assertEquals(13, j.getInt("token")); assertEquals(60, j.getInt("maxFPS"))
        assertFalse(c.takenBy(h.settings)); assertTrue(c.takenBy(old.settings))
        assertEquals(60, c.appliedTo(h.settings).maxFPS); assertEquals(true, c.appliedTo(h.settings).virtualDisplay)
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
