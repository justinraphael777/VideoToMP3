package com.justin.vid2mp3;

import mp3.BRHist;
import mp3.BitStream;
import mp3.GainAnalysis;
import mp3.GetAudio;
import mp3.ID3Tag;
import mp3.Lame;
import mp3.LameGlobalFlags;
import mp3.MPEGMode;
import mp3.Parse;
import mp3.Presets;
import mp3.Quantize;
import mp3.QuantizePVT;
import mp3.Reservoir;
import mp3.Takehiro;
import mp3.VBRTag;
import mp3.Version;
import mpg.Common;
import mpg.Interface;
import mpg.MPGLib;

/** Thin wrapper around the jump3r (pure-Java LAME) encoder for 16-bit PCM. */
public class Mp3Encoder {
    private final Lame lame;
    private final LameGlobalFlags gfp;
    private final int channels;
    private int[] left = new int[0];
    private int[] right = new int[0];

    public Mp3Encoder(int sampleRate, int channels, int bitrateKbps, int quality) {
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("channels must be 1 or 2");
        this.channels = channels;
        lame = new Lame();
        GetAudio gaud = new GetAudio();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro();
        Parse parse = new Parse();
        new BRHist();
        MPGLib mpg = new MPGLib();
        Interface intf = new Interface();
        Common common = new Common();

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        gaud.setModules(parse, mpg);
        parse.setModules(ver, id3, p);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        gfp = lame.lame_init();
        gfp.num_channels = channels;
        gfp.in_samplerate = sampleRate;
        gfp.brate = bitrateKbps;
        gfp.quality = quality;
        gfp.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        id3.id3tag_init(gfp);
        gfp.write_id3tag_automatic = false;
        gfp.findReplayGain = false;
        gfp.bWriteVbrTag = false;
        int rc = lame.lame_init_params(gfp);
        if (rc < 0) throw new IllegalArgumentException("Unsupported audio format (" + sampleRate + " Hz, " + channels + " ch)");
    }

    public int getOutSampleRate() { return gfp.out_samplerate; }

    /** Suggested output buffer size for a given number of input frames. */
    public static int outBufSize(int frames) { return (int) (1.25 * frames) + 7200; }

    /** Encode interleaved 16-bit PCM. Returns number of bytes written to out. */
    public int encode(short[] pcm, int frames, byte[] out) {
        if (left.length < frames) { left = new int[frames]; right = new int[frames]; }
        // jump3r expects samples in +/-32768 range (no scaling)
        if (channels == 2) {
            for (int i = 0, j = 0; i < frames; i++) {
                left[i] = pcm[j++];
                right[i] = pcm[j++];
            }
        } else {
            for (int i = 0; i < frames; i++) { left[i] = right[i] = pcm[i]; }
        }
        int n = lame.lame_encode_buffer_int(gfp, left, right, frames, out, 0, out.length);
        if (n < 0) throw new RuntimeException("MP3 encoder error " + n);
        return n;
    }

    public int flush(byte[] out) {
        return lame.lame_encode_flush(gfp, out, 0, out.length);
    }

    public void close() { lame.lame_close(gfp); }
}
