#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

typedef struct Mp3EncoderState {
    AVCodecContext *codec_ctx;
    SwrContext *swr_ctx;
    AVAudioFifo *fifo;
    AVFrame *frame;
    AVPacket *pkt;
    uint8_t **converted_data;
    int converted_capacity;
    int channels;
    int frame_size;
    int64_t next_pts;
} Mp3EncoderState;

static enum AVSampleFormat pick_mp3_sample_fmt(const AVCodec *codec) {
    const enum AVSampleFormat *p = codec->sample_fmts;
    if (!p) {
        return AV_SAMPLE_FMT_S16P;
    }
    while (*p != AV_SAMPLE_FMT_NONE) {
        if (*p == AV_SAMPLE_FMT_S16P) {
            return AV_SAMPLE_FMT_S16P;
        }
        p++;
    }
    p = codec->sample_fmts;
    while (*p != AV_SAMPLE_FMT_NONE) {
        if (*p == AV_SAMPLE_FMT_FLTP) {
            return AV_SAMPLE_FMT_FLTP;
        }
        p++;
    }
    return codec->sample_fmts[0];
}

static int ensure_converted_buffer(Mp3EncoderState *state, int needed_samples) {
    if (needed_samples <= state->converted_capacity && state->converted_data != NULL) {
        return 0;
    }
    if (state->converted_data != NULL) {
        av_freep(&state->converted_data[0]);
        av_freep(&state->converted_data);
        state->converted_capacity = 0;
    }
    int ret = av_samples_alloc_array_and_samples(
        &state->converted_data,
        NULL,
        state->channels,
        needed_samples,
        state->codec_ctx->sample_fmt,
        0
    );
    if (ret < 0) {
        state->converted_data = NULL;
        state->converted_capacity = 0;
        return ret;
    }
    state->converted_capacity = needed_samples;
    return 0;
}

static void free_encoder_state(Mp3EncoderState *state) {
    if (!state) return;
    if (state->converted_data) {
        av_freep(&state->converted_data[0]);
        av_freep(&state->converted_data);
    }
    if (state->fifo) {
        av_audio_fifo_free(state->fifo);
    }
    if (state->swr_ctx) {
        swr_free(&state->swr_ctx);
    }
    if (state->frame) {
        av_frame_free(&state->frame);
    }
    if (state->pkt) {
        av_packet_free(&state->pkt);
    }
    if (state->codec_ctx) {
        avcodec_free_context(&state->codec_ctx);
    }
    free(state);
}

static int drain_encoded_packets(
    JNIEnv *env,
    AVCodecContext *ctx,
    AVPacket *pkt,
    jbyteArray out_buf,
    int out_cap,
    int *written_offset
) {
    int ret;
    while ((ret = avcodec_receive_packet(ctx, pkt)) >= 0) {
        if (pkt->size > 0 && (*written_offset + pkt->size) <= out_cap) {
            (*env)->SetByteArrayRegion(
                env,
                out_buf,
                (jsize)(*written_offset),
                (jsize)pkt->size,
                (const jbyte *)pkt->data
            );
            *written_offset += pkt->size;
        }
        av_packet_unref(pkt);
    }
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
        return 0;
    }
    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeGetCapabilityInfo(
    JNIEnv *env,
    jobject thiz
) {
    (void)thiz;
    const AVCodec *enc = avcodec_find_encoder_by_name("libmp3lame");
    const AVCodec *dec = avcodec_find_decoder(AV_CODEC_ID_MP3);
    char info[192];
    snprintf(
        info,
        sizeof(info),
        "FFmpeg %s (MP3-only: encoder=%s, decoder=%s)",
        av_version_info(),
        enc ? enc->name : "missing",
        dec ? dec->name : "missing"
    );
    return (*env)->NewStringUTF(env, info);
}

JNIEXPORT jlong JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeCreateEncoder(
    JNIEnv *env,
    jobject thiz,
    jint sample_rate_hz,
    jint channels,
    jint bitrate_kbps,
    jint quality
) {
    (void)env;
    (void)thiz;

    if (sample_rate_hz <= 0 || (channels != 1 && channels != 2) || bitrate_kbps <= 0) {
        return 0L;
    }

    const AVCodec *codec = avcodec_find_encoder_by_name("libmp3lame");
    if (!codec) {
        return 0L;
    }

    Mp3EncoderState *state = (Mp3EncoderState *)calloc(1, sizeof(Mp3EncoderState));
    if (!state) {
        return 0L;
    }

    state->channels = channels;
    state->codec_ctx = avcodec_alloc_context3(codec);
    if (!state->codec_ctx) {
        free_encoder_state(state);
        return 0L;
    }

    state->codec_ctx->bit_rate = (int64_t)bitrate_kbps * 1000LL;
    state->codec_ctx->sample_rate = sample_rate_hz;
    state->codec_ctx->sample_fmt = pick_mp3_sample_fmt(codec);
    av_channel_layout_default(&state->codec_ctx->ch_layout, channels);
    state->codec_ctx->compression_level = quality;

    if (avcodec_open2(state->codec_ctx, codec, NULL) < 0) {
        free_encoder_state(state);
        return 0L;
    }

    state->frame_size = state->codec_ctx->frame_size > 0 ? state->codec_ctx->frame_size : 1152;

    AVChannelLayout in_layout;
    av_channel_layout_default(&in_layout, channels);
    int swr_ret = swr_alloc_set_opts2(
        &state->swr_ctx,
        &state->codec_ctx->ch_layout,
        state->codec_ctx->sample_fmt,
        state->codec_ctx->sample_rate,
        &in_layout,
        AV_SAMPLE_FMT_S16,
        sample_rate_hz,
        0,
        NULL
    );
    av_channel_layout_uninit(&in_layout);

    if (swr_ret < 0 || !state->swr_ctx || swr_init(state->swr_ctx) < 0) {
        free_encoder_state(state);
        return 0L;
    }

    state->fifo = av_audio_fifo_alloc(
        state->codec_ctx->sample_fmt,
        channels,
        state->frame_size * 4
    );
    state->frame = av_frame_alloc();
    state->pkt = av_packet_alloc();
    if (!state->fifo || !state->frame || !state->pkt) {
        free_encoder_state(state);
        return 0L;
    }

    return (jlong)(intptr_t)state;
}

JNIEXPORT jint JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeEncodePcm16(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jshortArray pcm_samples,
    jint sample_count,
    jbyteArray out_mp3_buffer
) {
    (void)thiz;
    Mp3EncoderState *state = (Mp3EncoderState *)(intptr_t)handle;
    if (!state || !pcm_samples || !out_mp3_buffer || sample_count <= 0) {
        return 0;
    }

    int in_frames = sample_count / state->channels;
    if (in_frames <= 0) {
        return 0;
    }

    if (ensure_converted_buffer(state, in_frames + 64) < 0) {
        return -1;
    }

    jshort *pcm_ptr = (*env)->GetShortArrayElements(env, pcm_samples, NULL);
    if (!pcm_ptr) {
        return -1;
    }

    const uint8_t *in_planes[1] = { (const uint8_t *)pcm_ptr };
    int converted = swr_convert(
        state->swr_ctx,
        state->converted_data,
        state->converted_capacity,
        in_planes,
        in_frames
    );
    (*env)->ReleaseShortArrayElements(env, pcm_samples, pcm_ptr, JNI_ABORT);

    if (converted < 0) {
        return -1;
    }

    if (converted > 0) {
        if (av_audio_fifo_realloc(state->fifo, av_audio_fifo_size(state->fifo) + converted) < 0) {
            return -1;
        }
        if (av_audio_fifo_write(state->fifo, (void **)state->converted_data, converted) < converted) {
            return -1;
        }
    }

    int out_cap = (int)(*env)->GetArrayLength(env, out_mp3_buffer);
    int total_written = 0;

    while (av_audio_fifo_size(state->fifo) >= state->frame_size) {
        av_frame_unref(state->frame);
        state->frame->nb_samples = state->frame_size;
        state->frame->format = state->codec_ctx->sample_fmt;
        state->frame->sample_rate = state->codec_ctx->sample_rate;
        av_channel_layout_copy(&state->frame->ch_layout, &state->codec_ctx->ch_layout);

        if (av_frame_get_buffer(state->frame, 0) < 0) {
            return -1;
        }
        if (av_audio_fifo_read(state->fifo, (void **)state->frame->data, state->frame_size) < state->frame_size) {
            return -1;
        }

        state->frame->pts = state->next_pts;
        state->next_pts += state->frame_size;

        int send_ret = avcodec_send_frame(state->codec_ctx, state->frame);
        av_frame_unref(state->frame);
        if (send_ret < 0) {
            return -1;
        }

        if (drain_encoded_packets(
                env,
                state->codec_ctx,
                state->pkt,
                out_mp3_buffer,
                out_cap,
                &total_written
            ) < 0) {
            return -1;
        }
    }

    return total_written;
}

JNIEXPORT jint JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeFlushEncoder(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jbyteArray out_mp3_buffer
) {
    (void)thiz;
    Mp3EncoderState *state = (Mp3EncoderState *)(intptr_t)handle;
    if (!state || !out_mp3_buffer) {
        return 0;
    }

    int out_cap = (int)(*env)->GetArrayLength(env, out_mp3_buffer);
    int total_written = 0;

    int remaining = av_audio_fifo_size(state->fifo);
    if (remaining > 0) {
        av_frame_unref(state->frame);
        state->frame->nb_samples = remaining;
        state->frame->format = state->codec_ctx->sample_fmt;
        state->frame->sample_rate = state->codec_ctx->sample_rate;
        av_channel_layout_copy(&state->frame->ch_layout, &state->codec_ctx->ch_layout);

        if (av_frame_get_buffer(state->frame, 0) >= 0) {
            if (av_audio_fifo_read(state->fifo, (void **)state->frame->data, remaining) > 0) {
                state->frame->pts = state->next_pts;
                state->next_pts += remaining;
                avcodec_send_frame(state->codec_ctx, state->frame);
            }
        }
        av_frame_unref(state->frame);
        drain_encoded_packets(env, state->codec_ctx, state->pkt, out_mp3_buffer, out_cap, &total_written);
    }

    if (avcodec_send_frame(state->codec_ctx, NULL) >= 0) {
        drain_encoded_packets(env, state->codec_ctx, state->pkt, out_mp3_buffer, out_cap, &total_written);
    }

    return total_written;
}

JNIEXPORT void JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeDestroyEncoder(
    JNIEnv *env,
    jobject thiz,
    jlong handle
) {
    (void)env;
    (void)thiz;
    Mp3EncoderState *state = (Mp3EncoderState *)(intptr_t)handle;
    free_encoder_state(state);
}

static int encode_and_write_mux_frame(
    AVCodecContext *enc_ctx,
    AVFormatContext *ofmt,
    AVStream *out_stream,
    AVFrame *frame,
    AVPacket *pkt
) {
    int ret = avcodec_send_frame(enc_ctx, frame);
    if (ret < 0) return ret;

    while ((ret = avcodec_receive_packet(enc_ctx, pkt)) >= 0) {
        av_packet_rescale_ts(pkt, enc_ctx->time_base, out_stream->time_base);
        pkt->stream_index = out_stream->index;
        ret = av_interleaved_write_frame(ofmt, pkt);
        av_packet_unref(pkt);
        if (ret < 0) return ret;
    }
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
        return 0;
    }
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_android_abdullah_voicerecorder_mp3_audio_FfmpegMp3Native_nativeConvertToMp3(
    JNIEnv *env,
    jobject thiz,
    jstring input_path_j,
    jstring output_path_j,
    jint bitrate_kbps,
    jint target_sample_rate,
    jint target_channels
) {
    (void)thiz;
    if (!input_path_j || !output_path_j) return -1;

    const char *input_path = (*env)->GetStringUTFChars(env, input_path_j, NULL);
    const char *output_path = (*env)->GetStringUTFChars(env, output_path_j, NULL);
    if (!input_path || !output_path) {
        if (input_path) (*env)->ReleaseStringUTFChars(env, input_path_j, input_path);
        if (output_path) (*env)->ReleaseStringUTFChars(env, output_path_j, output_path);
        return -1;
    }

    AVFormatContext *ifmt = NULL;
    AVFormatContext *ofmt = NULL;
    AVCodecContext *dec_ctx = NULL;
    AVCodecContext *enc_ctx = NULL;
    SwrContext *swr = NULL;
    AVAudioFifo *fifo = NULL;
    AVFrame *dec_frame = NULL;
    AVFrame *enc_frame = NULL;
    AVPacket *pkt = NULL;
    uint8_t **converted = NULL;
    int converted_cap = 0;
    int ret = 0;

    if ((ret = avformat_open_input(&ifmt, input_path, NULL, NULL)) < 0) goto cleanup;
    if ((ret = avformat_find_stream_info(ifmt, NULL)) < 0) goto cleanup;

    int stream_idx = av_find_best_stream(ifmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream_idx < 0) {
        ret = stream_idx;
        goto cleanup;
    }

    AVStream *in_stream = ifmt->streams[stream_idx];
    const AVCodec *dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
    if (!dec) {
        ret = AVERROR_DECODER_NOT_FOUND;
        goto cleanup;
    }

    dec_ctx = avcodec_alloc_context3(dec);
    if (!dec_ctx) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }
    if ((ret = avcodec_parameters_to_context(dec_ctx, in_stream->codecpar)) < 0) goto cleanup;
    if ((ret = avcodec_open2(dec_ctx, dec, NULL)) < 0) goto cleanup;

    if ((ret = avformat_alloc_output_context2(&ofmt, NULL, "mp3", output_path)) < 0) goto cleanup;
    const AVCodec *enc = avcodec_find_encoder_by_name("libmp3lame");
    if (!enc) {
        ret = AVERROR_ENCODER_NOT_FOUND;
        goto cleanup;
    }

    AVStream *out_stream = avformat_new_stream(ofmt, NULL);
    if (!out_stream) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }

    enc_ctx = avcodec_alloc_context3(enc);
    if (!enc_ctx) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }

    int out_sr = target_sample_rate > 0 ? target_sample_rate : dec_ctx->sample_rate;
    int out_ch = (target_channels == 1 || target_channels == 2)
        ? target_channels
        : (dec_ctx->ch_layout.nb_channels > 1 ? 2 : 1);

    enc_ctx->bit_rate = (int64_t)(bitrate_kbps > 0 ? bitrate_kbps : 192) * 1000LL;
    enc_ctx->sample_rate = out_sr;
    enc_ctx->sample_fmt = pick_mp3_sample_fmt(enc);
    av_channel_layout_default(&enc_ctx->ch_layout, out_ch);
    enc_ctx->time_base = (AVRational){1, out_sr};
    if (ofmt->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if ((ret = avcodec_open2(enc_ctx, enc, NULL)) < 0) goto cleanup;
    if ((ret = avcodec_parameters_from_context(out_stream->codecpar, enc_ctx)) < 0) goto cleanup;
    out_stream->time_base = enc_ctx->time_base;

    if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&ofmt->pb, output_path, AVIO_FLAG_WRITE)) < 0) goto cleanup;
    }
    if ((ret = avformat_write_header(ofmt, NULL)) < 0) goto cleanup;

    if ((ret = swr_alloc_set_opts2(
            &swr,
            &enc_ctx->ch_layout,
            enc_ctx->sample_fmt,
            enc_ctx->sample_rate,
            &dec_ctx->ch_layout,
            dec_ctx->sample_fmt,
            dec_ctx->sample_rate,
            0,
            NULL)) < 0) {
        goto cleanup;
    }
    if ((ret = swr_init(swr)) < 0) goto cleanup;

    int enc_frame_size = enc_ctx->frame_size > 0 ? enc_ctx->frame_size : 1152;
    fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, out_ch, enc_frame_size * 4);
    dec_frame = av_frame_alloc();
    enc_frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (!fifo || !dec_frame || !enc_frame || !pkt) {
        ret = AVERROR(ENOMEM);
        goto cleanup;
    }

    int64_t pts = 0;
    while (av_read_frame(ifmt, pkt) >= 0) {
        if (pkt->stream_index == stream_idx) {
            ret = avcodec_send_packet(dec_ctx, pkt);
            av_packet_unref(pkt);
            if (ret < 0) goto cleanup;

            while (avcodec_receive_frame(dec_ctx, dec_frame) >= 0) {
                int max_out = (int)av_rescale_rnd(
                    swr_get_delay(swr, dec_ctx->sample_rate) + dec_frame->nb_samples,
                    enc_ctx->sample_rate,
                    dec_ctx->sample_rate,
                    AV_ROUND_UP
                ) + 64;
                if (max_out > converted_cap) {
                    if (converted) {
                        av_freep(&converted[0]);
                        av_freep(&converted);
                    }
                    ret = av_samples_alloc_array_and_samples(
                        &converted,
                        NULL,
                        out_ch,
                        max_out,
                        enc_ctx->sample_fmt,
                        0
                    );
                    if (ret < 0) goto cleanup;
                    converted_cap = max_out;
                }
                int conv = swr_convert(
                    swr,
                    converted,
                    converted_cap,
                    (const uint8_t **)dec_frame->extended_data,
                    dec_frame->nb_samples
                );
                av_frame_unref(dec_frame);
                if (conv < 0) {
                    ret = conv;
                    goto cleanup;
                }
                if (conv > 0) {
                    av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + conv);
                    av_audio_fifo_write(fifo, (void **)converted, conv);
                }
                while (av_audio_fifo_size(fifo) >= enc_frame_size) {
                    av_frame_unref(enc_frame);
                    enc_frame->nb_samples = enc_frame_size;
                    enc_frame->format = enc_ctx->sample_fmt;
                    enc_frame->sample_rate = enc_ctx->sample_rate;
                    av_channel_layout_copy(&enc_frame->ch_layout, &enc_ctx->ch_layout);
                    if ((ret = av_frame_get_buffer(enc_frame, 0)) < 0) goto cleanup;
                    av_audio_fifo_read(fifo, (void **)enc_frame->data, enc_frame_size);
                    enc_frame->pts = pts;
                    pts += enc_frame_size;
                    ret = encode_and_write_mux_frame(enc_ctx, ofmt, out_stream, enc_frame, pkt);
                    av_frame_unref(enc_frame);
                    if (ret < 0) goto cleanup;
                }
            }
        } else {
            av_packet_unref(pkt);
        }
    }

    // Drain decoder
    avcodec_send_packet(dec_ctx, NULL);
    while (avcodec_receive_frame(dec_ctx, dec_frame) >= 0) {
        int max_out = (int)av_rescale_rnd(
            swr_get_delay(swr, dec_ctx->sample_rate) + dec_frame->nb_samples,
            enc_ctx->sample_rate,
            dec_ctx->sample_rate,
            AV_ROUND_UP
        ) + 64;
        if (max_out > converted_cap) {
            if (converted) {
                av_freep(&converted[0]);
                av_freep(&converted);
            }
            if ((ret = av_samples_alloc_array_and_samples(&converted, NULL, out_ch, max_out, enc_ctx->sample_fmt, 0)) < 0) {
                goto cleanup;
            }
            converted_cap = max_out;
        }
        int conv = swr_convert(swr, converted, converted_cap, (const uint8_t **)dec_frame->extended_data, dec_frame->nb_samples);
        av_frame_unref(dec_frame);
        if (conv > 0) {
            av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + conv);
            av_audio_fifo_write(fifo, (void **)converted, conv);
        }
    }

    int rem = av_audio_fifo_size(fifo);
    if (rem > 0) {
        av_frame_unref(enc_frame);
        enc_frame->nb_samples = rem;
        enc_frame->format = enc_ctx->sample_fmt;
        enc_frame->sample_rate = enc_ctx->sample_rate;
        av_channel_layout_copy(&enc_frame->ch_layout, &enc_ctx->ch_layout);
        if ((ret = av_frame_get_buffer(enc_frame, 0)) >= 0) {
            av_audio_fifo_read(fifo, (void **)enc_frame->data, rem);
            enc_frame->pts = pts;
            pts += rem;
            encode_and_write_mux_frame(enc_ctx, ofmt, out_stream, enc_frame, pkt);
        }
        av_frame_unref(enc_frame);
    }

    encode_and_write_mux_frame(enc_ctx, ofmt, out_stream, NULL, pkt);
    ret = av_write_trailer(ofmt);

cleanup:
    if (converted) {
        av_freep(&converted[0]);
        av_freep(&converted);
    }
    if (fifo) av_audio_fifo_free(fifo);
    if (dec_frame) av_frame_free(&dec_frame);
    if (enc_frame) av_frame_free(&enc_frame);
    if (pkt) av_packet_free(&pkt);
    if (swr) swr_free(&swr);
    if (dec_ctx) avcodec_free_context(&dec_ctx);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (ofmt) {
        if (!(ofmt->oformat->flags & AVFMT_NOFILE) && ofmt->pb) {
            avio_closep(&ofmt->pb);
        }
        avformat_free_context(ofmt);
    }
    if (ifmt) avformat_close_input(&ifmt);

    (*env)->ReleaseStringUTFChars(env, input_path_j, input_path);
    (*env)->ReleaseStringUTFChars(env, output_path_j, output_path);
    return ret;
}
