package com.skillmasterai.api;

/**
 * What "end every other device" ended.
 *
 * <p>The one write on this plane that answers with a body. The design's confirmation says how many
 * devices were affected (「退出后，其他 N 台设备都需要重新登录」), and N is known before the call
 * from the list the console is already showing — but only the server knows how many were still there
 * when the call arrived. Sending it back is what makes the sentence afterwards a statement rather
 * than a guess: a device ended from another tab a minute ago would otherwise be counted twice.
 *
 * @param revoked how many sessions were ended. Zero is a normal answer and not a failure — it means
 *                there was nothing else signed in, which is also what a second click finds
 */
record SessionsRevoked(int revoked) {
}
