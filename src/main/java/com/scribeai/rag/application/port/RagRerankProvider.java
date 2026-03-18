package com.scribeai.rag.application.port;

import com.scribeai.rag.application.model.RagSearchHit;

import java.util.List;

public interface RagRerankProvider {

    // 후보 청크 재정렬
    List<RagSearchHit> rerank(String query, List<RagSearchHit> candidates, int topN);
}
