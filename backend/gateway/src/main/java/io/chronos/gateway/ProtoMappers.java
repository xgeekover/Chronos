package io.chronos.gateway;

import io.chronos.api.parse.Quality;

/** Converts core value objects to their protobuf wire form (§9). */
public final class ProtoMappers {

    private ProtoMappers() {}

    public static io.chronos.proto.TagValue toProto(io.chronos.api.parse.TagValue v) {
        return io.chronos.proto.TagValue.newBuilder()
                .setTag(v.tagKey())
                .setValue(v.value() == null ? "" : String.valueOf(v.value()))
                .setQuality(io.chronos.proto.Quality.forNumber(v.quality().ordinal()))
                .setTsEpochMillis(v.ts().toEpochMilli())
                .build();
    }

    public static io.chronos.proto.TagValue fromSample(String tag, Object value, Quality quality, long tsMillis) {
        return io.chronos.proto.TagValue.newBuilder()
                .setTag(tag)
                .setValue(value == null ? "" : String.valueOf(value))
                .setQuality(io.chronos.proto.Quality.forNumber(quality.ordinal()))
                .setTsEpochMillis(tsMillis)
                .build();
    }
}
