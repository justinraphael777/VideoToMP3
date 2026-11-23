package com.justin.vid2mp3;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Audio enhancement chain, pure Java so it runs on any phone.
 * Works on a raw interleaved 16-bit little-endian PCM file and writes the
 * processed result as an MP3.
 */
public class AudioFx {

    /** User settings for the Enhance module. Strength/amount values are 0..1. */
    public static class Settings {
        public boolean noiseReduction;
        public float noiseStrength = 0.6f;
        public boolean clarity;
        public boolean reverb;
        public float reverbAmount = 0.4f;
        public boolean normalize = true;

        public boolean anyEnabled() { return noiseReduction || clarity || reverb || normalize; }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (noiseReduction) sb.append("noise reduction ").append(Math.round(noiseStrength * 100)).append("%, ");
            if (clarity) sb.append("voice clarity, ");
            if (reverb) sb.append("reverb ").append(Math.round(reverbAmount * 100)).append("%, ");
            if (normalize) sb.append("normalized, ");
            if (sb.length() == 0) return "no effects";
            return sb.substring(0, sb.length() - 2);
        }
    }

    public interface Progress { void update(float fraction, String label); }

    private static final int BLOCK = 4096; // frames per processing block

    /**
     * Runs the effect chain on pcmFile and encodes the result to MP3 into out.
     * Progress is reported in [0,1].
     */
    public static void process(File pcmFile, int sampleRate, int channels, Settings s, File workDir,
                               OutputStream out, int bitrate, int quality, Progress progress) throws IOException {
        long totalFrames = pcmFile.length() / (2L * channels);
        if (totalFrames == 0) throw new IOException("No audio to process");

        // ---- Stage 1: analyse noise (only if needed) ----
        float[][] noiseProfile = null;
        if (s.noiseReduction) {
            progress.update(0f, "Analysing background noise…");
            noiseProfile = NoiseReducer.estimateNoise(pcmFile, channels, totalFrames);
        }

        // ---- Stage 2: run the chain into a float temp file, tracking the peak ----
        File tmp = new File(workDir, "fx.f32");
        NoiseReducer[] nr = null;
        Biquad[][] eq = null;
        Freeverb reverb = null;
        int latency = 0;
        if (s.noiseReduction && noiseProfile != null) {
            nr = new NoiseReducer[channels];
            for (int c = 0; c < channels; c++) nr[c] = new NoiseReducer(noiseProfile[c], s.noiseStrength);
            latency += NoiseReducer.N;
        }
        if (s.clarity) {
            eq = new Biquad[channels][];
            for (int c = 0; c < channels; c++) {
                eq[c] = new Biquad[] {
                        Biquad.highPass(sampleRate, 90, 0.707),
                        Biquad.peaking(sampleRate, 250, 1.0, -2.0),   // reduce boominess
                        Biquad.peaking(sampleRate, 3200, 0.9, 4.0),   // presence
                        Biquad.highShelf(sampleRate, 9000, 1.5)       // a little air
                };
            }
        }
        if (s.reverb) reverb = new Freeverb(sampleRate, s.reverbAmount);
        int tail = s.reverb ? (int) (sampleRate * (1.0 + 2.0 * s.reverbAmount)) : 0;

        long outFrames = totalFrames + tail;
        long inputNeeded = totalFrames + tail + latency;
        float peak = 0f;

        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(pcmFile), 1 << 16));
        DataOutputStream fo = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            byte[] raw = new byte[BLOCK * channels * 2];
            float[][] buf = new float[channels][BLOCK];
            long fed = 0, skipped = 0, written = 0;
            while (fed < inputNeeded) {
                int n = (int) Math.min(BLOCK, inputNeeded - fed);
                int got = 0;
                if (fed < totalFrames) {
                    int want = (int) Math.min(n, totalFrames - fed);
                    got = readFully(in, raw, want * channels * 2) / (2 * channels);
                    for (int i = 0, b = 0; i < got; i++) {
                        for (int c = 0; c < channels; c++, b += 2) {
                            buf[c][i] = (short) ((raw[b] & 0xff) | (raw[b + 1] << 8)) / 32768f;
                        }
                    }
                }
                for (int c = 0; c < channels; c++) Arrays.fill(buf[c], got, n, 0f);

                if (nr != null) for (int c = 0; c < channels; c++) nr[c].process(buf[c], n);
                if (eq != null) for (int c = 0; c < channels; c++) for (Biquad f : eq[c]) f.process(buf[c], n);
                if (reverb != null) reverb.process(buf, channels, n);
                fed += n;

                // drop the leading latency samples, keep outFrames samples
                int start = 0;
                if (skipped < latency) {
                    start = (int) Math.min(n, latency - skipped);
                    skipped += start;
                }
                for (int i = start; i < n && written < outFrames; i++, written++) {
                    for (int c = 0; c < channels; c++) {
                        float v = buf[c][i];
                        float a = Math.abs(v);
                        if (a > peak) peak = a;
                        fo.writeFloat(v);
                    }
                }
                progress.update(0.05f + 0.45f * fed / (float) inputNeeded, "Applying effects…");
            }
        } finally {
            in.close();
            fo.close();
        }

        // ---- Stage 3: gain (normalize / anti-clip) and MP3 encode ----
        float gain = 1f;
        if (peak > 1e-6f) {
            float target = 0.891f; // -1 dBFS
            if (s.normalize) gain = target / peak;
            else if (peak > 0.99f) gain = 0.99f / peak;
        }
        if (s.normalize) gain = Math.min(gain, 20f); // never amplify silence/noise by more than +26 dB

        Mp3Encoder enc = new Mp3Encoder(sampleRate, channels, bitrate, quality);
        DataInputStream fi = new DataInputStream(new BufferedInputStream(new FileInputStream(tmp), 1 << 16));
        try {
            short[] pcm = new short[BLOCK * channels];
            byte[] mp3 = new byte[Mp3Encoder.outBufSize(BLOCK)];
            long done = 0;
            while (done < outFrames) {
                int n = (int) Math.min(BLOCK, outFrames - done);
                for (int i = 0; i < n * channels; i++) {
                    float v = fi.readFloat() * gain;
                    if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                    pcm[i] = (short) Math.round(v * 32767f);
                }
                int len = enc.encode(pcm, n, mp3);
                out.write(mp3, 0, len);
                done += n;
                progress.update(0.5f + 0.5f * done / (float) outFrames, "Encoding enhanced MP3…");
            }
            int len = enc.flush(mp3);
            out.write(mp3, 0, len);
        } catch (EOFException e) {
            throw new IOException("Temporary file truncated");
        } finally {
            fi.close();
            enc.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return off - (off % 2);
    }

    // ===================================================================
    // Noise reduction: STFT spectral gating with a noise profile learned
    // from the quietest parts of the recording.
    // ===================================================================
    static class NoiseReducer {
        static final int N = 2048, HOP = 512, BINS = N / 2 + 1;
        static final float[] WIN = new float[N];
        static {
            // sqrt-Hann for analysis and synthesis; 75% overlap -> sum of Hann = 2
            for (int i = 0; i < N; i++) WIN[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        }

        private final float[] noise;   // noise power per bin
        private final float alpha, floor;
        private final float[] inBuf = new float[N], outAcc = new float[N], ready = new float[HOP];
        private final float[] re = new float[N], im = new float[N], gainPrev = new float[BINS], g = new float[BINS];
        private int pos = 0;

        NoiseReducer(float[] noisePower, float strength) {
            this.noise = noisePower;
            this.alpha = 1.0f + 3.0f * strength;                                  // over-subtraction
            this.floor = (float) Math.pow(10, (-8 - 22 * strength) / 20.0);       // -8 dB .. -30 dB
            Arrays.fill(gainPrev, 1f);
        }

        void process(float[] x, int n) {
            for (int i = 0; i < n; i++) {
                inBuf[N - HOP + pos] = x[i];
                x[i] = ready[pos];
                if (++pos == HOP) { pos = 0; frame(); }
            }
        }

        private void frame() {
            for (int i = 0; i < N; i++) { re[i] = inBuf[i] * WIN[i]; im[i] = 0f; }
            FFT.fft(re, im, false);
            for (int k = 0; k < BINS; k++) {
                float p = re[k] * re[k] + im[k] * im[k] + 1e-12f;
                float gk = 1f - alpha * noise[k] / p;
                g[k] = gk < floor ? floor : gk;
            }
            // smooth across frequency (3-bin) and time (fast attack, slow release)
            float prevRaw = g[0];
            for (int k = 0; k < BINS; k++) {
                float cur = g[k];
                float next = k + 1 < BINS ? g[k + 1] : cur;
                float sm = 0.25f * prevRaw + 0.5f * cur + 0.25f * next;
                prevRaw = cur;
                float gp = gainPrev[k];
                float gs = sm > gp ? 0.5f * gp + 0.5f * sm : 0.8f * gp + 0.2f * sm;
                gainPrev[k] = gs;
            }
            for (int k = 0; k < BINS; k++) {
                float gs = gainPrev[k];
                re[k] *= gs; im[k] *= gs;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }
            }
            FFT.fft(re, im, true);
            for (int i = 0; i < N; i++) outAcc[i] += re[i] * WIN[i] * 0.5f;
            System.arraycopy(outAcc, 0, ready, 0, HOP);
            System.arraycopy(outAcc, HOP, outAcc, 0, N - HOP);
            Arrays.fill(outAcc, N - HOP, N, 0f);
            System.arraycopy(inBuf, HOP, inBuf, 0, N - HOP);
        }

        /** Learn the noise spectrum from the quietest ~15% of frames (ignoring digital silence). */
        static float[][] estimateNoise(File pcm, int channels, long totalFrames) throws IOException {
            int frames = (int) Math.max(1, (totalFrames - N) / HOP + 1);
            float[] energy = new float[frames];
            // pass 1: frame energies (mono mix)
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(pcm), 1 << 16));
            try {
                byte[] raw = new byte[HOP * channels * 2];
                float acc = 0; int fi = 0;
                float[] hopE = new float[N / HOP];
                long read = 0;
                while (fi < frames) {
                    int got = readFully(in, raw, raw.length) / (2 * channels);
                    if (got == 0) break;
                    float e = 0;
                    for (int i = 0; i < got * channels; i++) {
                        float v = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8)) / 32768f;
                        e += v * v;
                    }
                    hopE[(int) (read % hopE.length)] = e;
                    read++;
                    if (read >= hopE.length) {
                        float sum = 0; for (float h : hopE) sum += h;
                        energy[fi++] = sum / (N * channels);
                    }
                }
                frames = Math.max(1, fi);
            } finally { in.close(); }

            float[] sorted = Arrays.copyOf(energy, frames);
            Arrays.sort(sorted);
            int firstNonSilent = 0;
            while (firstNonSilent < frames - 1 && sorted[firstNonSilent] < 1e-9f) firstNonSilent++;
            int idx = firstNonSilent + (int) ((frames - firstNonSilent) * 0.15f);
            float threshold = sorted[Math.min(frames - 1, idx)];

            // pass 2: average power spectra of quiet frames (at most ~600 of them)
            int quietCount = 0;
            for (int f = 0; f < frames; f++) if (energy[f] <= threshold && energy[f] >= 1e-9f) quietCount++;
            int stride = Math.max(1, quietCount / 600);
            float[][] prof = new float[channels][BINS];
            int used = 0;
            float[] re = new float[N], im = new float[N];
            float[][] win = new float[channels][N];
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(pcm), 1 << 16));
            try {
                byte[] raw = new byte[HOP * channels * 2];
                long hops = 0; int q = 0;
                while (true) {
                    int got = readFully(in, raw, raw.length) / (2 * channels);
                    if (got < HOP) break;
                    for (int c = 0; c < channels; c++) System.arraycopy(win[c], HOP, win[c], 0, N - HOP);
                    for (int i = 0; i < HOP; i++)
                        for (int c = 0; c < channels; c++) {
                            int b = 2 * (i * channels + c);
                            win[c][N - HOP + i] = (short) ((raw[b] & 0xff) | (raw[b + 1] << 8)) / 32768f;
                        }
                    hops++;
                    if (hops < N / HOP) continue;
                    int f = (int) (hops - N / HOP);
                    if (f >= frames) break;
                    if (energy[f] > threshold || energy[f] < 1e-9f) continue;
                    if ((q++ % stride) != 0) continue;
                    for (int c = 0; c < channels; c++) {
                        for (int i = 0; i < N; i++) { re[i] = win[c][i] * WIN[i]; im[i] = 0; }
                        FFT.fft(re, im, false);
                        for (int k = 0; k < BINS; k++) prof[c][k] += re[k] * re[k] + im[k] * im[k];
                    }
                    used++;
                }
            } finally { in.close(); }
            if (used == 0) return null;
            for (int c = 0; c < channels; c++) for (int k = 0; k < BINS; k++) prof[c][k] /= used;
            return prof;
        }
    }

    // ===================================================================
    // Radix-2 FFT (in place). inverse=true includes the 1/N scaling.
    // ===================================================================
    static class FFT {
        private static float[] cos, sin; private static int size;

        static synchronized void init(int n) {
            if (size == n) return;
            cos = new float[n / 2]; sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) { cos[i] = (float) Math.cos(2 * Math.PI * i / n); sin[i] = (float) Math.sin(2 * Math.PI * i / n); }
            size = n;
        }

        static void fft(float[] re, float[] im, boolean inverse) {
            int n = re.length;
            init(n);
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int step = n / len, half = len >> 1;
                for (int i = 0; i < n; i += len) {
                    for (int k = 0; k < half; k++) {
                        float wr = cos[k * step], wi = inverse ? sin[k * step] : -sin[k * step];
                        int a = i + k, b = a + half;
                        float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi;
                        re[a] += xr; im[a] += xi;
                    }
                }
            }
            if (inverse) { float s = 1f / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
        }
    }

    // ===================================================================
    // Biquad filters (RBJ cookbook)
    // ===================================================================
    static class Biquad {
        double b0, b1, b2, a1, a2, z1, z2;

        static Biquad make(double b0, double b1, double b2, double a0, double a1, double a2) {
            Biquad q = new Biquad();
            q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0;
            return q;
        }

        static Biquad highPass(int sr, double f, double Q) {
            double w = 2 * Math.PI * f / sr, cw = Math.cos(w), al = Math.sin(w) / (2 * Q);
            return make((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al);
        }

        static Biquad peaking(int sr, double f, double Q, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / sr, cw = Math.cos(w), al = Math.sin(w) / (2 * Q);
            return make(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A);
        }

        static Biquad highShelf(int sr, double f, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / sr, cw = Math.cos(w), sw = Math.sin(w);
            double al = sw / 2 * Math.sqrt(2), sA = 2 * Math.sqrt(A) * al;
            return make(A * ((A + 1) + (A - 1) * cw + sA), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - sA),
                    (A + 1) - (A - 1) * cw + sA, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - sA);
        }

        float tick(float x) {
            double out = b0 * x + z1;
            z1 = b1 * x - a1 * out + z2;
            z2 = b2 * x - a2 * out;
            return (float) out;
        }

        void process(float[] x, int n) {
            for (int i = 0; i < n; i++) {
                double in = x[i];
                double out = b0 * in + z1;
                z1 = b1 * in - a1 * out + z2;
                z2 = b2 * in - a2 * out;
                x[i] = (float) out;
            }
        }
    }

    // ===================================================================
    // Freeverb (Jezar's classic Schroeder/Moorer reverb), stereo.
    // ===================================================================
    static class Freeverb {
        static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static final int[] ALLPASS = {556, 441, 341, 225};
        static final int SPREAD = 23;

        final float[][][] combBuf = new float[2][8][], apBuf = new float[2][4][];
        final int[][] combIdx = new int[2][8], apIdx = new int[2][4];
        final float[][] combStore = new float[2][8];
        final float feedback, damp, wet, dry;
        final Biquad[] preHp = new Biquad[2];
        final float[][] preDelay; final int[] pdIdx = new int[2];

        Freeverb(int sr, float amount) {
            double scale = sr / 44100.0;
            for (int ch = 0; ch < 2; ch++) {
                int sp = ch == 0 ? 0 : SPREAD;
                for (int i = 0; i < 8; i++) combBuf[ch][i] = new float[Math.max(1, (int) ((COMBS[i] + sp) * scale))];
                for (int i = 0; i < 4; i++) apBuf[ch][i] = new float[Math.max(1, (int) ((ALLPASS[i] + sp) * scale))];
                preHp[ch] = Biquad.highPass(sr, 200, 0.707); // keep the low end of the reverb clean
            }
            preDelay = new float[2][Math.max(1, (int) (sr * (0.01 + 0.03 * amount)))];
            float room = 0.70f + 0.28f * amount;       // 0.70..0.98
            feedback = room;
            damp = 0.45f - 0.2f * amount;
            wet = (0.10f + 0.35f * amount) * 3f;        // freeverb "scalewet"
            dry = 1.0f - 0.25f * amount;
        }

        void process(float[][] buf, int channels, int n) {
            for (int i = 0; i < n; i++) {
                float inL = buf[0][i], inR = channels > 1 ? buf[1][i] : inL;
                float mono = (inL + inR) * 0.5f;
                float outL = 0, outR = 0;
                for (int ch = 0; ch < 2; ch++) {
                    float hp = preHp[ch].tick(mono);
                    float[] pd = preDelay[ch];
                    float delayed = pd[pdIdx[ch]];
                    pd[pdIdx[ch]] = hp;
                    pdIdx[ch] = (pdIdx[ch] + 1) % pd.length;
                    float input = delayed * 0.015f;
                    float acc = 0;
                    for (int c = 0; c < 8; c++) {
                        float[] b = combBuf[ch][c];
                        int idx = combIdx[ch][c];
                        float o = b[idx];
                        combStore[ch][c] = o * (1 - damp) + combStore[ch][c] * damp;
                        b[idx] = input + combStore[ch][c] * feedback;
                        combIdx[ch][c] = idx + 1 == b.length ? 0 : idx + 1;
                        acc += o;
                    }
                    for (int a = 0; a < 4; a++) {
                        float[] b = apBuf[ch][a];
                        int idx = apIdx[ch][a];
                        float bo = b[idx];
                        float o = -acc + bo;
                        b[idx] = acc + bo * 0.5f;
                        apIdx[ch][a] = idx + 1 == b.length ? 0 : idx + 1;
                        acc = o;
                    }
                    if (ch == 0) outL = acc; else outR = acc;
                }
                if (channels > 1) {
                    buf[0][i] = inL * dry + outL * wet;
                    buf[1][i] = inR * dry + outR * wet;
                } else {
                    buf[0][i] = inL * dry + (outL + outR) * 0.5f * wet;
                }
            }
        }
    }
}
