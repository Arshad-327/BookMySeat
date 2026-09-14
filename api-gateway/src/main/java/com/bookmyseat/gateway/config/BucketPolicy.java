package com.bookmyseat.gateway.config;

/**
 * One token-bucket budget. A token bucket is two numbers, not one "per minute" figure:
 *
 * <ul>
 *   <li><b>capacity</b> - the burst. How many requests can arrive back to back from a full
 *       bucket. A page load that fires several requests at once spends this.</li>
 *   <li><b>refill rate</b> - the sustained rate. Tokens added per second, up to capacity.</li>
 * </ul>
 *
 * <p>The most a client can send in its first minute is therefore capacity + 60 x refill
 * rate, not the refill rate alone.
 *
 * @param name           part of the Redis key, so each policy has its own bucket
 * @param capacity       bucket size, the burst
 * @param refillPerMinute sustained rate, as tokens per minute because that is how people
 *                       read a limit; converted to per second for the script
 */
public record BucketPolicy(String name, int capacity, double refillPerMinute) {

    public BucketPolicy {
        if (name == null || name.isBlank() || name.contains(":")) {
            throw new IllegalArgumentException("rate-limit policy name must be non-blank and contain no ':' - got " + name);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("rate-limit policy " + name + ": capacity must be at least 1");
        }
        if (!(refillPerMinute > 0)) {
            throw new IllegalArgumentException("rate-limit policy " + name + ": refill-per-minute must be positive");
        }
    }

    /** What the script takes as ARGV[2]. */
    public double refillPerSecond() {
        return refillPerMinute / 60.0;
    }

    /**
     * How long an idle bucket's key may live, which the script takes as ARGV[4].
     *
     * <p><b>Never shorter than the time an empty bucket takes to refill.</b> A key that
     * expires sooner hands an idle client a brand-new full bucket before refilling would
     * have given it one, which lets through more than the limit. At exactly the refill time
     * the two agree: by the moment the key expires the bucket would have been full anyway.
     * The original used a fixed 60 seconds, which is only right for buckets that refill
     * within a minute, so the floor of 60 is kept and raised when a policy needs longer.
     */
    public long keyTtlSeconds() {
        long secondsToRefillFromEmpty = (long) Math.ceil(capacity / refillPerSecond());
        return Math.max(60L, secondsToRefillFromEmpty);
    }
}
