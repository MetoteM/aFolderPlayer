package dev.alex.folderplayer;

/** Ten independent peaking filters per channel. No automatic attenuation. */
public final class DspEqualizer {
    public static final double[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final class Settings {
        public final boolean enabled;
        public final double preampDb;
        private final double[] gains;
        public Settings(boolean enabled, double preampDb, double[] gains) {
            this.enabled = enabled;
            this.preampDb = finiteClamp(preampDb, -12, 0);
            this.gains = new double[FREQUENCIES.length];
            for (int i = 0; i < this.gains.length; i++)
                this.gains[i] = finiteClamp(i < gains.length ? gains[i] : 0, -12, 12);
        }
        private static double finiteClamp(double x, double min, double max) {
            return Double.isFinite(x) ? Math.max(min, Math.min(max, x)) : 0;
        }
        public double gain(int i) { return gains[i]; }
        public boolean isBypass() {
            if (!enabled) return true;
            if (preampDb != 0) return false;
            for (double gain : gains) if (gain != 0) return false;
            return true;
        }
    }
    private final double[][] coefficients = new double[FREQUENCIES.length][5];
    private double[][] z1 = new double[0][0], z2 = new double[0][0];
    private Settings settings = new Settings(false, 0, new double[FREQUENCIES.length]);
    private int sampleRate;
    private double gain = 1;
    private boolean bypass = true;
    public void configure(int sampleRate, int channels, Settings settings) {
        if (sampleRate <= 0 || channels <= 0) throw new IllegalArgumentException("Invalid audio format");
        this.sampleRate = sampleRate;
        z1 = new double[channels][FREQUENCIES.length]; z2 = new double[channels][FREQUENCIES.length];
        setSettings(settings);
    }
    public void setSettings(Settings settings) {
        this.settings = settings;
        bypass = settings.isBypass();
        gain = Math.pow(10, settings.preampDb / 20);
        for (int i = 0; i < FREQUENCIES.length; i++) {
            double[] c = coefficients[i];
            if (settings.gain(i) == 0 || FREQUENCIES[i] >= sampleRate * 0.49) {
                c[0] = 1; c[1] = c[2] = c[3] = c[4] = 0; continue;
            }
            double a = Math.pow(10, settings.gain(i) / 40);
            double w = 2 * Math.PI * FREQUENCIES[i] / sampleRate;
            double alpha = Math.sin(w) / (2 * 1.0); // Q = 1
            double cos = Math.cos(w), a0 = 1 + alpha / a;
            c[0] = (1 + alpha * a) / a0;
            c[1] = -2 * cos / a0;
            c[2] = (1 - alpha * a) / a0;
            c[3] = -2 * cos / a0;
            c[4] = (1 - alpha / a) / a0;
        }
        reset();
    }
    public boolean isBypass() { return bypass; }
    public void reset() {
        for (double[] row : z1) java.util.Arrays.fill(row, 0);
        for (double[] row : z2) java.util.Arrays.fill(row, 0);
    }
    public double process(double sample, int channel) {
        if (isBypass()) return sample;
        double x = sample * gain;
        for (int band = 0; band < FREQUENCIES.length; band++) {
            double[] c = coefficients[band];
            double y = c[0] * x + z1[channel][band];
            z1[channel][band] = c[1] * x - c[3] * y + z2[channel][band];
            z2[channel][band] = c[2] * x - c[4] * y;
            x = y;
        }
        return x;
    }
}
