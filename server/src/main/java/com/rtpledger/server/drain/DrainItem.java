package com.rtpledger.server.drain;

import com.rtpledger.server.chronicle.PostingResult;

public record DrainItem(PostingResult posting, long chronicleIndex) {
}
