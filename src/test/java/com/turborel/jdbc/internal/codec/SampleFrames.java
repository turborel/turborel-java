package com.turborel.jdbc.internal.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The protocol's sample frames, {@code doc/client/05-protocol-frames.tsv} of Turborel's main at
 * 045e42a9, copied beside these tests (RULES.md D6): one frame a line, as the page's Sample frames
 * lays the file out ([PROTOCOL Sample frames]).
 */
final class SampleFrames {
    /** The fields of a line, apart by one tab each. */
    static final int FIELDS = 5;

    /** The file, as the tests read it from their resources. */
    static final String FILE = "05-protocol-frames.tsv";

    private SampleFrames() {}

    /**
     * A sample: who sends it, its name, the capabilities it needs, the frame and what it means.
     *
     * @param direction {@code client} or {@code server}
     * @param name its name, unique in the file
     * @param needs the capabilities it needs granted, none for {@code -}
     * @param frame the whole frame, its header too
     * @param means what it decodes to, in words
     */
    record Sample(String direction, String name, List<String> needs, Octets frame, String means) {
        /** The whole frame's bytes. */
        byte[] bytes() {
            return frame.toByteArray();
        }

        /** The frame's kind. */
        byte kind() {
            return bytes()[0];
        }

        /** The frame's body, after its five-byte header. */
        byte[] body() {
            byte[] all = bytes();
            return Arrays.copyOfRange(all, FrameHeader.BYTES, all.length);
        }
    }

    /**
     * The sample of {@code name}.
     *
     * @param name its name
     * @return the sample
     */
    static Sample named(String name) {
        Sample sample = all().get(name);
        if (sample == null) {
            throw new IllegalStateException("no sample is named " + name);
        }
        return sample;
    }

    /**
     * The sample of {@code name} among {@code samples}.
     *
     * @param samples the samples, by name
     * @param name its name
     * @return the sample
     */
    static Sample in(Map<String, Sample> samples, String name) {
        Sample sample = samples.get(name);
        if (sample == null) {
            throw new IllegalStateException("no sample is named " + name);
        }
        return sample;
    }

    /** Each line of the file that is no comment, as it is written. */
    static List<String[]> lines() {
        try (InputStream in = SampleFrames.class.getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException(FILE + " is not among the test resources");
            }
            List<String[]> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line.split("\t", -1));
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every sample, by its name. */
    static Map<String, Sample> all() {
        Map<String, Sample> samples = new TreeMap<>();
        for (String[] f : lines()) {
            List<String> needs = f[2].equals("-") ? List.of() : List.of(f[2].split(",", -1));
            samples.put(f[1], new Sample(f[0], f[1], needs, Octets.of(Fixture.hex(f[3])), f[4]));
        }
        return samples;
    }

    /** The samples one side sends whose names begin {@code prefix}, by name. */
    static Map<String, Sample> of(String direction, String prefix) {
        Map<String, Sample> chosen = new TreeMap<>();
        all().forEach((name, sample) -> {
            if (sample.direction().equals(direction) && name.startsWith(prefix)) {
                chosen.put(name, sample);
            }
        });
        return chosen;
    }
}
