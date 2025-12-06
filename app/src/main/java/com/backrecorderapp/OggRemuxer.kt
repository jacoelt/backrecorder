package com.backrecorderapp

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32

/**
 * Rebuild a clean Ogg file from several source Ogg files (Opus).
 * Produces a seekable output with corrected page headers, sequence numbers and CRCs.
 */
object OggRemuxer {

    private const val OGG_PAGE_HEADER_BASE = 27
    private const val CAPTURE_PATTERN = "OggS"
    private const val BOS_FLAG = 0x02
    private const val EOS_FLAG = 0x04
    private const val CONTINUATION_FLAG = 0x01

    private data class Packet(
        val data: ByteArray,
        val isPageEnd: Boolean,
        val granulePositionIfPageEnd: Long? // absolute granule if this packet was the last in a source page
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Packet

            if (!data.contentEquals(other.data)) return false
            if (isPageEnd != other.isPageEnd) return false
            if (granulePositionIfPageEnd != other.granulePositionIfPageEnd) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + isPageEnd.hashCode()
            result = 31 * result + (granulePositionIfPageEnd?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Main entrypoint: takes ordered sourceFiles (oldest -> newest) and writes a clean single Ogg file.
     */
    fun rebuildOgg(sourceFiles: List<File>, output: File) {
        if (sourceFiles.isEmpty()) return
        val packets = mutableListOf<Packet>()

        // 1) Parse pages from each source and extract packets, preserving page-end granules.
        var fileOffsetGranule = 0L // cumulative granule offset between files
        for (src in sourceFiles) {
            val (filePackets, fileMaxGranule) = parseOggFileToPackets(src, fileOffsetGranule)
            packets.addAll(filePackets)
            // Advance offset by last granule of this file (if none, keep same)
            if (fileMaxGranule > 0) fileOffsetGranule = fileMaxGranule
        }

        // 2) Write packets into new Ogg pages and output file
        writePacketsToOgg(packets, output)
    }

    // ---------- Parsing ----------

    private fun parseOggFileToPackets(src: File, granuleOffset: Long): Pair<List<Packet>, Long> {
        val packets = mutableListOf<Packet>()
        var maxGranuleInFile = granuleOffset
        FileInputStream(src).use { fis ->
            val fileBytes = fis.readBytes()
            var pos = 0
            val total = fileBytes.size

            // We'll reassemble packets across pages
            val packetBuffer = ByteBufferOutput()

            while (pos + OGG_PAGE_HEADER_BASE <= total) {
                // check capture pattern
                val cap = String(fileBytes, pos, 4)
                if (cap != CAPTURE_PATTERN) {
                    // not an Ogg page — stop parsing
                    break
                }
                // Header fields
                val headerType = fileBytes[pos + 5].toInt() and 0xFF

                // granule position: 8 bytes little-endian starting at pos+6
                val granulePos = readLELong(fileBytes, pos + 6)
                val bitstreamSerialNumber = readLEInt(fileBytes, pos + 14)
                val pageSeqNo = readLEInt(fileBytes, pos + 18)

                // crc is at pos+22 (we don't need here)
                val pageSegments = fileBytes[pos + 26].toInt() and 0xFF
                val segmentTableStart = pos + OGG_PAGE_HEADER_BASE
                val segmentTableEnd = segmentTableStart + pageSegments
                if (segmentTableEnd > total) break // invalid

                // read segment table
                val segments = IntArray(pageSegments)
                var segSum = 0
                for (i in 0 until pageSegments) {
                    segments[i] = fileBytes[segmentTableStart + i].toInt() and 0xFF
                    segSum += segments[i]
                }

                val pageDataStart = segmentTableEnd
                val pageDataEnd = pageDataStart + segSum
                if (pageDataEnd > total) break // invalid

                // iterate segments to rebuild packets
                var segIdx = 0
                while (segIdx < pageSegments) {
                    val sb = packetBuffer
                    var finishedPacket = false
                    while (segIdx < pageSegments) {
                        val segLen = segments[segIdx]
                        val segOff = pageDataStart + (segments.take(segIdx).sum())
                        // copy the segment bytes
                        sb.write(fileBytes, segOff, segLen)
                        segIdx++
                        if (segLen < 255) {
                            // end of packet
                            finishedPacket = true
                            break
                        }
                        // else packet continues across more segments
                    }
                    if (finishedPacket) {
                        val packetBytes = sb.toByteArrayAndReset()
                        // Is this the last packet of the page?
                        val isLastPacketOfPage = (segIdx == pageSegments)
                        val granuleAbs = if (isLastPacketOfPage) {
                            // page's granule pos applies to the last packet on this page
                            // add file granule offset to make them cumulative
                            granulePos + granuleOffset
                        } else null

                        if (granuleAbs != null && granuleAbs > maxGranuleInFile) {
                            maxGranuleInFile = granuleAbs
                        }
                        packets.add(Packet(packetBytes, isLastPacketOfPage, granuleAbs))
                    } else {
                        // Packet didn't end in this page (should only happen if last segment is 255 and page ended).
                        // Keep current buffer and continue to next page: do nothing here.
                        break
                    }
                }

                // move to next page
                pos = pageDataEnd
            } // while pages

            return Pair(packets, maxGranuleInFile)
        }
    }

    // ---------- Writing ----------

    private fun writePacketsToOgg(packets: List<Packet>, output: File) {
        FileOutputStream(output).use { fos ->
            var pageSeq = 0
            val serial = (System.currentTimeMillis() and 0xFFFFFFFF).toInt() // arbitrary serial for stream
            var prevGranule = 0L

            val pageBuilder = PageBuilder(serial)

            // We'll ensure OpusHead + OpusTags (if present) remain at beginning.
            // We also ensure we flush pages when encountering packets marked as pageEnd so we reuse original granules.

            for ((index, packet) in packets.withIndex()) {
                val willFlushBecauseSourcePageEnds = packet.isPageEnd
                pageBuilder.addPacket(packet.data)

                if (willFlushBecauseSourcePageEnds || pageBuilder.isSegmentTableFull()) {
                    // determine granule for this page: if the last packet we added had a recorded granule, use it;
                    // otherwise fallback to previous granule (safe but less precise).
                    val lastPacketGranule = packet.granulePositionIfPageEnd
                    val granulePosForPage = lastPacketGranule ?: prevGranule

                    val isBOS = pageSeq == 0
                    val isEOS = (index == packets.size - 1)

                    val headerFlags = (if (isBOS) BOS_FLAG else 0) or (if (isEOS) EOS_FLAG else 0) or (if (pageBuilder.pageStartsWithContinuation) CONTINUATION_FLAG else 0)

                    val pageBytes = pageBuilder.buildPage(pageSeq, headerFlags, granulePosForPage ?: 0L)
                    fos.write(pageBytes)

                    // update page seq and prevGranule
                    pageSeq++
                    if (lastPacketGranule != null) prevGranule = lastPacketGranule
                    // reset builder for next page
                    pageBuilder.resetForNextPage()
                }
            }

            // If any remaining data in pageBuilder, flush it as final page
            if (!pageBuilder.isEmpty()) {
                val isBOS = pageSeq == 0
                val isEOS = true
                val headerFlags = (if (isBOS) BOS_FLAG else 0) or (if (isEOS) EOS_FLAG else 0) or (if (pageBuilder.pageStartsWithContinuation) CONTINUATION_FLAG else 0)
                val granulePosForPage = prevGranule
                val pageBytes = pageBuilder.buildPage(pageSeq, headerFlags, granulePosForPage)
                fos.write(pageBytes)
            }
        }
    }

    // Helper for reading little-endian ints/longs
    private fun readLEInt(buf: ByteArray, pos: Int): Int {
        return (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
    }
    private fun readLELong(buf: ByteArray, pos: Int): Long {
        val lo = readLEInt(buf, pos).toLong() and 0xFFFFFFFFL
        val hi = readLEInt(buf, pos + 4).toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    // ---------- PageBuilder ----------

    /**
     * Helper that accumulates packets and creates Ogg pages with proper segment table
     */
    private class PageBuilder(private val serial: Int) {
        private val packetBuffers = mutableListOf<ByteArray>()
        private val segments = mutableListOf<Int>()
        var pageStartsWithContinuation = false
            private set

        // total segments used in current page
        fun currentSegmentCount(): Int = segments.size

        fun addPacket(packet: ByteArray) {
            packetBuffers.add(packet)
            // count segments
            var off = 0
            while (off < packet.size) {
                val take = minOf(255, packet.size - off)
                segments.add(take)
                off += take
            }
        }

        fun isSegmentTableFull(): Boolean {
            // limit: segment table length cannot exceed 255
            return segments.size >= 255
        }

        fun isEmpty(): Boolean = packetBuffers.isEmpty()

        fun resetForNextPage() {
            packetBuffers.clear()
            segments.clear()
            pageStartsWithContinuation = false
        }

        fun buildPage(pageSeq: Int, headerFlags: Int, granulePos: Long): ByteArray {
            // Build page data bytes from packetBuffers and segments
            val bodySize = segments.sum()
            val headerSegments = segments.size
            val header = ByteArray(OGG_PAGE_HEADER_BASE + headerSegments)
            // capture
            header[0] = 'O'.code.toByte()
            header[1] = 'g'.code.toByte()
            header[2] = 'g'.code.toByte()
            header[3] = 'S'.code.toByte()
            header[4] = 0x00 // version
            header[5] = headerFlags.toByte()
            // granule pos (little-endian 8 bytes)
            writeLELong(header, 6, granulePos)
            // serial
            writeLEInt(header, 14, serial)
            // page seq
            writeLEInt(header, 18, pageSeq)
            // crc placeholder (0)
            writeLEInt(header, 22, 0)
            // page segments
            header[26] = headerSegments.toByte()
            // segment table
            var idx = 27
            var segSum = 0
            for (s in segments) {
                header[idx++] = (s and 0xFF).toByte()
                segSum += s
            }

            // Build body
            val body = ByteArray(bodySize)
            var bodyOff = 0
            for (p in packetBuffers) {
                var off = 0
                while (off < p.size) {
                    val take = minOf(255, p.size - off)
                    System.arraycopy(p, off, body, bodyOff, take)
                    bodyOff += take
                    off += take
                }
            }

            // Combine header + body into buffer for CRC calc
            val page = ByteArray(header.size + body.size)
            System.arraycopy(header, 0, page, 0, header.size)
            System.arraycopy(body, 0, page, header.size, body.size)

            // Compute CRC32
            val crc = CRC32()
            // CRC covers the entire page with the CRC field zeroed (we already set it to zero)
            crc.update(page, 0, page.size)
            val crcValue = crc.value.toInt()

            // write CRC little endian into header bytes 22..25
            writeLEInt(page, 22, crcValue)

            return page
        }

        private fun writeLEInt(buf: ByteArray, pos: Int, value: Int) {
            buf[pos] = (value and 0xFF).toByte()
            buf[pos + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[pos + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[pos + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun writeLELong(buf: ByteArray, pos: Int, value: Long) {
            writeLEInt(buf, pos, (value and 0xFFFFFFFFL).toInt())
            writeLEInt(buf, pos + 4, ((value ushr 32) and 0xFFFFFFFFL).toInt())
        }
    }

    // ---------- small helpers ----------

    // lightweight byte accumulator with reset
    private class ByteBufferOutput {
        private var buffer = ByteArray(4096)
        private var size = 0
        fun write(src: ByteArray, off: Int, len: Int) {
            ensureCapacity(size + len)
            System.arraycopy(src, off, buffer, size, len)
            size += len
        }
        fun toByteArrayAndReset(): ByteArray {
            val out = buffer.copyOf(size)
            size = 0
            return out
        }
        private fun ensureCapacity(min: Int) {
            if (buffer.size < min) {
                var newCap = buffer.size * 2
                while (newCap < min) newCap *= 2
                buffer = buffer.copyOf(newCap)
            }
        }
    }

    private fun writeLEInt(buf: ByteArray, pos: Int, value: Int) {
        buf[pos] = (value and 0xFF).toByte()
        buf[pos + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[pos + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[pos + 3] = ((value ushr 24) and 0xFF).toByte()
    }
    private fun writeLELong(buf: ByteArray, pos: Int, value: Long) {
        writeLEInt(buf, pos, (value and 0xFFFFFFFFL).toInt())
        writeLEInt(buf, pos + 4, ((value ushr 32) and 0xFFFFFFFFL).toInt())
    }
}
