package io.github.xfzz7.dualpane;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;

/** Orders decoders so a pane can prefer hardware or software. Fallback stays enabled. */
@UnstableApi
final class CodecOrder {
    private CodecOrder() {}

    static MediaCodecSelector softwareFirst() {
        return (mimeType, secure, tunneling) -> reorder(mimeType, secure, tunneling, true);
    }

    static MediaCodecSelector hardwareFirst() {
        return (mimeType, secure, tunneling) -> reorder(mimeType, secure, tunneling, false);
    }

    private static List<MediaCodecInfo> reorder(
            String mimeType, boolean secure, boolean tunneling, boolean softwareFirst)
            throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
        ArrayList<MediaCodecInfo> software = new ArrayList<>();
        ArrayList<MediaCodecInfo> hardware = new ArrayList<>();
        for (MediaCodecInfo info : all) {
            if (info.softwareOnly) {
                software.add(info);
            } else {
                hardware.add(info);
            }
        }
        if (softwareFirst) {
            software.addAll(hardware);
            return software;
        }
        hardware.addAll(software);
        return hardware;
    }
}
