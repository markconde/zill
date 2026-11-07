// Makes the stand-in Mac's sound: a few seconds of a tone, encoded as AAC-ELD the way Sill for Mac
// encodes (AudioConverter, 48 kHz stereo Float32 in, 480 frames a packet, 128 kbps), written as
//   u32 cookie length · cookie (the converter's magic cookie, as kind 43's format carries it)
//   u32 priming frames · then each packet as u16 length · bytes   (all big endian)
// Usage: swiftc -O tools/eld-clip.swift -o build/eld-clip && build/eld-clip out.bin
import AudioToolbox
import Foundation

let rate = 48_000.0, framesPerPacket: UInt32 = 480, seconds = 4.0
var input = AudioStreamBasicDescription(mSampleRate: rate, mFormatID: kAudioFormatLinearPCM,
    mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked, mBytesPerPacket: 8, mFramesPerPacket: 1,
    mBytesPerFrame: 8, mChannelsPerFrame: 2, mBitsPerChannel: 32, mReserved: 0)
var output = AudioStreamBasicDescription(mSampleRate: rate, mFormatID: kAudioFormatMPEG4AAC_ELD, mFormatFlags: 0,
    mBytesPerPacket: 0, mFramesPerPacket: framesPerPacket, mBytesPerFrame: 0, mChannelsPerFrame: 2,
    mBitsPerChannel: 0, mReserved: 0)
var converter: AudioConverterRef?
func check(_ s: OSStatus, _ what: String) { if s != noErr { fatalError("\(what): \(s)") } }
check(AudioConverterNew(&input, &output, &converter), "new")
let conv = converter!
var bitrate: UInt32 = 128_000
check(AudioConverterSetProperty(conv, kAudioConverterEncodeBitRate, 4, &bitrate), "bitrate")

var cookieSize: UInt32 = 0
check(AudioConverterGetPropertyInfo(conv, kAudioConverterCompressionMagicCookie, &cookieSize, nil), "cookie size")
var cookie = [UInt8](repeating: 0, count: Int(cookieSize))
check(AudioConverterGetProperty(conv, kAudioConverterCompressionMagicCookie, &cookieSize, &cookie), "cookie")
var prime = AudioConverterPrimeInfo()
var primeSize = UInt32(MemoryLayout<AudioConverterPrimeInfo>.size)
_ = AudioConverterGetProperty(conv, kAudioConverterPrimeInfo, &primeSize, &prime)

// A 440 Hz tone on the left, 660 Hz on the right, with a short beep every second so a gap is audible.
let total = Int(rate * seconds)
var pcm = [Float](repeating: 0, count: total * 2)
for i in 0..<total {
    let t = Double(i) / rate
    let gate: Float = (t.truncatingRemainder(dividingBy: 1.0) < 0.25) ? 0.3 : 0.08
    pcm[2 * i] = gate * Float(sin(2 * .pi * 440 * t))
    pcm[2 * i + 1] = gate * Float(sin(2 * .pi * 660 * t))
}

final class Feed { var pcm: [Float]; var at = 0; init(_ p: [Float]) { pcm = p } }
let feed = Feed(pcm)
let supply: AudioConverterComplexInputDataProc = { _, packets, data, _, user in
    let f = Unmanaged<Feed>.fromOpaque(user!).takeUnretainedValue()
    let left = (f.pcm.count / 2) - f.at
    if left <= 0 { packets.pointee = 0; data.pointee.mBuffers.mDataByteSize = 0; return noErr }
    let n = min(Int(packets.pointee), left)
    f.pcm.withUnsafeMutableBufferPointer { b in
        data.pointee.mBuffers.mData = UnsafeMutableRawPointer(b.baseAddress! + f.at * 2)
    }
    data.pointee.mBuffers.mDataByteSize = UInt32(n * 8)
    data.pointee.mBuffers.mNumberChannels = 2
    packets.pointee = UInt32(n)
    f.at += n
    return noErr
}

var out = Data()
func be32(_ v: UInt32) { withUnsafeBytes(of: v.bigEndian) { out.append(contentsOf: $0) } }
func be16(_ v: UInt16) { withUnsafeBytes(of: v.bigEndian) { out.append(contentsOf: $0) } }
be32(UInt32(cookie.count)); out.append(contentsOf: cookie)
be32(prime.leadingFrames)
var buffer = [UInt8](repeating: 0, count: 4096)
var count = 0
while true {
    var n: UInt32 = 1
    var desc = AudioStreamPacketDescription()
    let status: OSStatus = buffer.withUnsafeMutableBytes { raw in
        var list = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: 2, mDataByteSize: 4096, mData: raw.baseAddress))
        let s = AudioConverterFillComplexBuffer(conv, supply, Unmanaged.passUnretained(feed).toOpaque(), &n, &list, &desc)
        if s == noErr && n == 1 { be16(UInt16(list.mBuffers.mDataByteSize)); out.append(raw.baseAddress!.assumingMemoryBound(to: UInt8.self), count: Int(list.mBuffers.mDataByteSize)) }
        return s
    }
    if status != noErr || n == 0 { break }
    count += 1
}
try! out.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("cookie \(cookie.count) bytes: \(cookie.map { String(format: "%02x", $0) }.joined()) · priming \(prime.leadingFrames) · \(count) packets")
