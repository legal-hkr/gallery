package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpFactsTest {
    /** The shape a Pixel writes: motion photo and Ultra HDR, the directory's items as attributes. */
    private val pixel = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
          xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
          hdrgm:Version="1.0" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="968644">
          <Container:Directory><rdf:Seq>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="35710"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2961582" Item:Padding="0"/></rdf:li>
          </rdf:Seq></Container:Directory>
        </rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()

    @Test
    fun pixelMotionPhotoWithGainmap() {
        val facts = XmpFacts.parse(pixel)
        assertTrue(facts.motion)
        assertEquals(2961582L, facts.motionLength)
        assertTrue(facts.gainmap)
    }

    @Test
    fun elementsInsteadOfAttributes() {
        val xmp = """
            <rdf:Description xmlns:Camera="http://ns.google.com/photos/1.0/camera/">
              <Camera:MotionPhoto>1</Camera:MotionPhoto>
              <Container:Directory><rdf:Seq><rdf:li><Container:Item>
                <Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>1234</Item:Length>
              </Container:Item></rdf:li></rdf:Seq></Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val facts = XmpFacts.parse(xmp)
        assertTrue(facts.motion)
        assertEquals(1234L, facts.motionLength)
        assertFalse(facts.gainmap)
    }

    @Test
    fun olderMicroVideo() {
        val facts = XmpFacts.parse("""<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="4242"/>""")
        assertTrue(facts.motion)
        assertEquals(4242L, facts.motionLength)
    }

    @Test
    fun motionOffIsNotMotion() {
        val facts = XmpFacts.parse("""<rdf:Description GCamera:MotionPhoto="0" GCamera:MotionPhotoVersion="1"/>""")
        assertFalse(facts.motion)
        assertNull(facts.motionLength)
    }

    @Test
    fun ultraHdrOnly() {
        val facts = XmpFacts.parse("""<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/>""")
        assertTrue(facts.gainmap)
        assertFalse(facts.motion)
    }

    @Test
    fun nothing() {
        assertEquals(XmpFacts.NONE, XmpFacts.parse(null))
        assertEquals(XmpFacts.NONE, XmpFacts.parse("""<rdf:Description xmp:CreatorTool="GIMP"/>"""))
    }

    @Test
    fun offsets() {
        assertEquals(60, XmpFacts.offsetMinutes("+01:00"))
        assertEquals(-330, XmpFacts.offsetMinutes("-05:30"))
        assertEquals(0, XmpFacts.offsetMinutes("Z"))
        assertEquals(120, XmpFacts.offsetMinutes("+0200"))
        assertNull(XmpFacts.offsetMinutes("   "))
        assertNull(XmpFacts.offsetMinutes("+25:00"))
        assertNull(XmpFacts.offsetMinutes(null))
    }
}
