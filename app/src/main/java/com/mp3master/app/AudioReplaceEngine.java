package com.mp3master.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class AudioReplaceEngine {

    public static final int SAMPLE_RATE = 24000;
    private static final int NF = 512;
    private static final int NB = 24;
    public static final int NC = 12;

    private static final float[] COS = new float[NF];
    private static final float[] SIN = new float[NF];

    static {
        for (int i = 0; i < NF; i++) {
            double ang = 2.0 * Math.PI * i / NF;
            COS[i] = (float) Math.cos(ang);
            SIN[i] = (float) Math.sin(ang);
        }
    }

    public static class FeatureSet {
        public final float[] F;
        public final int n;
        public final double hopT;
        public final double winT;

        public FeatureSet(float[] F, int n, double hopT, double winT) {
            this.F = F;
            this.n = n;
            this.hopT = hopT;
            this.winT = winT;
        }
    }

    public static class DtwResult {
        public final float[] sc;
        public final int[] st;

        public DtwResult(float[] sc, int[] st) {
            this.sc = sc;
            this.st = st;
        }
    }

    public static class Candidate {
        public final float score;
        public final double startSec;
        public final double endSec;

        public Candidate(float score, double startSec, double endSec) {
            this.score = score;
            this.startSec = startSec;
            this.endSec = endSec;
        }
    }

    public interface ProgressCallback {
        void onProgress(float fraction);
    }

    public static FeatureSet extractFeatures(short[] x, int rate) {
        int R = Math.max(1, Math.round(rate / 16000f));
        double fs = (double) rate / R;
        int m = x.length / R;
        float[] y = new float[m];
        float invScale = 1f / (32768f * R);
        for (int i = 0; i < m; i++) {
            int sum = 0;
            int base = i * R;
            for (int k = 0; k < R; k++) {
                sum += x[base + k];
            }
            y[i] = sum * invScale;
        }

        int win = (int) Math.round(fs * 0.025);
        int hop = (int) Math.round(fs * 0.020);
        int n = Math.max(0, (m - win) / hop + 1);

        double hi = Math.min(7000.0, fs / 2.0 - 100.0);
        double melLo = 2595.0 * Math.log10(1.0 + 100.0 / 700.0);
        double melHi = 2595.0 * Math.log10(1.0 + hi / 700.0);
        int[] pts = new int[NB + 2];
        for (int i = 0; i < NB + 2; i++) {
            double mel = melLo + (melHi - melLo) * i / (NB + 1);
            double hz = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
            pts[i] = Math.min(NF / 2, Math.max(0, (int) Math.floor((NF + 1) * hz / fs)));
        }

        float[][] dct = new float[NC][NB];
        for (int c = 0; c < NC; c++) {
            for (int b = 0; b < NB; b++) {
                dct[c][b] = (float) Math.cos(Math.PI * (c + 1) * (b + 0.5) / NB);
            }
        }

        float[] hann = new float[win];
        for (int i = 0; i < win; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (win - 1)));
        }

        float[] F = new float[n * NC];
        float[] re = new float[NF];
        float[] im = new float[NF];
        float[] lm = new float[NB];

        for (int t = 0; t < n; t++) {
            Arrays.fill(re, 0f);
            Arrays.fill(im, 0f);
            int offset = t * hop;
            for (int i = 0; i < win; i++) {
                re[i] = y[offset + i] * hann[i];
            }

            // Bit-reversal permutation
            for (int i = 1, j = 0; i < NF; i++) {
                int bit = NF >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) {
                    float tmp = re[i];
                    re[i] = re[j];
                    re[j] = tmp;
                }
            }

            // Cooley-Tukey FFT
            for (int len = 2; len <= NF; len <<= 1) {
                int half = len >> 1;
                int step = NF / len;
                for (int i = 0; i < NF; i += len) {
                    for (int k = 0; k < half; k++) {
                        float c = COS[k * step];
                        float s = SIN[k * step];
                        int a = i + k;
                        int b = a + half;
                        float tr = re[b] * c + im[b] * s;
                        float ti = im[b] * c - re[b] * s;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }

            // Mel filterbank log energies
            for (int b = 0; b < NB; b++) {
                double e = 0.0;
                int l = pts[b], c = pts[b + 1], r = pts[b + 2];
                int dc = Math.max(1, c - l);
                for (int k = l; k < c; k++) {
                    e += (re[k] * re[k] + im[k] * im[k]) * (k - l) / dc;
                }
                int dr = Math.max(1, r - c);
                for (int k = c; k < r; k++) {
                    e += (re[k] * re[k] + im[k] * im[k]) * (r - k) / dr;
                }
                lm[b] = (float) Math.log(e + 1e-6);
            }

            // DCT -> MFCC
            int fBase = t * NC;
            for (int c = 0; c < NC; c++) {
                float s = 0f;
                float[] row = dct[c];
                for (int b = 0; b < NB; b++) {
                    s += row[b] * lm[b];
                }
                F[fBase + c] = s;
            }
        }

        // Cepstral mean & variance normalization
        int denom = Math.max(1, n);
        for (int c = 0; c < NC; c++) {
            double mu = 0.0;
            for (int t = 0; t < n; t++) mu += F[t * NC + c];
            mu /= denom;
            double v = 0.0;
            for (int t = 0; t < n; t++) {
                double d = F[t * NC + c] - mu;
                v += d * d;
            }
            float sd = (float) Math.sqrt(v / denom);
            if (sd == 0f) sd = 1f;
            float fMu = (float) mu;
            for (int t = 0; t < n; t++) {
                F[t * NC + c] = (F[t * NC + c] - fMu) / sd;
            }
        }

        return new FeatureSet(F, n, hop / fs, win / fs);
    }

    public static DtwResult subsequenceDtw(float[] A, int nA, float[] B, int offsetBFrames, int nB, ProgressCallback progress) {
        float[] pD = new float[nB];
        int[] pS = new int[nB];
        int[] pL = new int[nB];
        float[] cD = new float[nB];
        int[] cS = new int[nB];
        int[] cL = new int[nB];

        final float PEN = 0.5f;

        for (int j = 0; j < nB; j++) {
            pD[j] = frameDist(A, 0, B, offsetBFrames + j);
            pS[j] = j;
            pL[j] = 1;
        }

        int reportStep = Math.max(1, nA / 40);
        for (int i = 1; i < nA; i++) {
            for (int j = 0; j < nB; j++) {
                float c = frameDist(A, i, B, offsetBFrames + j);
                float bd = pD[j] + c + PEN;
                int bs = pS[j];
                int bl = pL[j] + 1;
                if (j > 0) {
                    float d = pD[j - 1] + c;
                    if (d < bd) {
                        bd = d;
                        bs = pS[j - 1];
                        bl = pL[j - 1] + 1;
                    }
                    float h = cD[j - 1] + c + PEN;
                    if (h < bd) {
                        bd = h;
                        bs = cS[j - 1];
                        bl = cL[j - 1] + 1;
                    }
                }
                cD[j] = bd;
                cS[j] = bs;
                cL[j] = bl;
            }
            float[] tD = pD; pD = cD; cD = tD;
            int[] tS = pS; pS = cS; cS = tS;
            int[] tL = pL; pL = cL; cL = tL;

            if (progress != null && (i % reportStep == 0 || i == nA - 1)) {
                progress.onProgress((float) i / Math.max(1, nA));
            }
        }

        float[] sc = new float[nB];
        for (int j = 0; j < nB; j++) {
            sc[j] = pD[j] / Math.max(1, pL[j]);
        }
        return new DtwResult(sc, pS);
    }

    private static float frameDist(float[] A, int i, float[] B, int j) {
        int aBase = i * NC;
        int bBase = j * NC;
        float s = 0f;
        for (int c = 0; c < NC; c++) {
            float d = A[aBase + c] - B[bBase + c];
            s += d * d;
        }
        return (float) Math.sqrt(s);
    }

    public static double snapToQuiet(short[] x, double tSec, int sr) {
        double rad = 0.15;
        int a = Math.max(0, (int) Math.floor((tSec - rad) * sr));
        int b = Math.min(x.length, (int) Math.floor((tSec + rad) * sr));
        int fr = Math.max(1, (int) Math.floor(0.005 * sr));
        int n = (b - a) / fr;
        if (n < 3) return tSec;

        double[] e = new double[n];
        for (int k = 0; k < n; k++) {
            double s = 0.0;
            int base = a + k * fr;
            for (int i = 0; i < fr; i++) {
                double v = x[base + i] / 32768.0;
                s += v * v;
            }
            e[k] = s;
        }

        int bi = 1;
        double bv = Double.MAX_VALUE;
        for (int k = 1; k < n - 1; k++) {
            double v = e[k - 1] + e[k] + e[k + 1];
            if (v < bv) {
                bv = v;
                bi = k;
            }
        }
        return (a + bi * fr + fr / 2.0) / sr;
    }

    public static short[] trimSilence(short[] x, int sr) {
        int thr = (int) Math.round(0.008 * 32768);
        int a = 0;
        int b = x.length - 1;
        while (a < b && Math.abs(x[a]) < thr) a++;
        while (b > a && Math.abs(x[b]) < thr) b--;
        int keep = (int) Math.floor(0.06 * sr);
        int start = Math.max(0, a - keep);
        int end = Math.min(x.length, b + keep);
        if (end <= start) return x;
        return Arrays.copyOfRange(x, start, end);
    }

    public static double rms(short[] x, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(x.length, to);
        int len = Math.max(1, end - start);
        double s = 0.0;
        for (int i = start; i < end; i++) {
            double v = x[i] / 32768.0;
            s += v * v;
        }
        return Math.sqrt(s / len);
    }

    public static short[] matchVolume(short[] surah, int a, int b, short[] frag) {
        double ro = rms(surah, a, b);
        double rf = rms(frag, 0, frag.length);
        double g = (ro > 0 && rf > 0) ? Math.min(1.6, Math.max(0.6, ro / rf)) : 1.0;
        short[] out = new short[frag.length];
        for (int i = 0; i < frag.length; i++) {
            int v = (int) Math.round(frag[i] * g);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            out[i] = (short) v;
        }
        return out;
    }

    public static short[] joinWithCrossfade(List<short[]> parts, int sr) {
        int n = (int) Math.floor(sr * 0.015);
        int totalLen = 0;
        for (short[] p : parts) totalLen += p.length;
        short[] out = new short[totalLen];
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            short[] p = parts.get(i);
            if (i > 0 && p.length >= 2 * n && parts.get(i - 1).length >= 2 * n) {
                pos -= n;
                for (int k = 0; k < n; k++) {
                    float f = (float) k / n;
                    int blended = Math.round(out[pos + k] * (1f - f) + p[k] * f);
                    out[pos + k] = (short) Math.max(-32768, Math.min(32767, blended));
                }
                System.arraycopy(p, n, out, pos + n, p.length - n);
                pos += p.length;
            } else {
                System.arraycopy(p, 0, out, pos, p.length);
                pos += p.length;
            }
        }
        return Arrays.copyOf(out, pos);
    }

    public static Double parseTimeSec(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            double t = 0.0;
            String[] parts = s.split(":");
            for (String p : parts) {
                double v = Double.parseDouble(p.trim());
                if (v < 0) return null;
                t = t * 60.0 + v;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fmtSec(double sec) {
        if (sec < 0) sec = 0;
        int m = (int) Math.floor(sec / 60.0);
        double rem = sec - m * 60.0;
        return String.format(Locale.US, "%d:%04.1f", m, rem);
    }

    public static List<Candidate> pickTopCandidates(
            DtwResult r, int j0, FeatureSet feat, int nA, short[] surah, int sr, boolean wordMode
    ) {
        int nB = r.sc.length;
        Integer[] idx = new Integer[nB];
        for (int j = 0; j < nB; j++) idx[j] = j;
        Arrays.sort(idx, (i1, i2) -> Float.compare(r.sc[i1], r.sc[i2]));

        int ex = wordMode ? Math.max(30, nA) : Math.max(150, nA);
        int maxPick = wordMode ? 5 : 3;
        List<Integer> picked = new ArrayList<>();
        for (int j : idx) {
            boolean farEnough = true;
            for (int q : picked) {
                if (Math.abs(q - j) <= ex) {
                    farEnough = false;
                    break;
                }
            }
            if (farEnough) {
                picked.add(j);
                if (picked.size() >= maxPick) break;
            }
        }

        List<Candidate> result = new ArrayList<>();
        for (int j : picked) {
            double rawStart = (r.st[j] + j0) * feat.hopT - 0.04;
            double rawEnd = (j + j0) * feat.hopT + feat.winT + 0.04;
            double s = snapToQuiet(surah, Math.max(0, rawStart), sr);
            double e = snapToQuiet(surah, Math.min((double) surah.length / sr, rawEnd), sr);
            result.add(new Candidate(r.sc[j], s, Math.max(s + 0.1, e)));
        }
        return result;
    }
}
