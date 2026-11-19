package com.mindrails.backend.knowledge.index;

import com.mindrails.backend.embedding.EmbeddingProvider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/** Bridge to the existing credential/provider catalog; preserves Agent memory compatibility. */
public final class ProviderEmbeddingModel implements EmbeddingModel {
    private final EmbeddingProvider provider;

    public ProviderEmbeddingModel(EmbeddingProvider provider) {
        this.provider = provider;
    }

    @Override
    public int dimension() {
        return provider.dimensions();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return Response.from(
                provider.embedAll(segments.stream().map(TextSegment::text).toList()).stream()
                        .map(
                                values -> {
                                    float[] vector = new float[values.size()];
                                    for (int i = 0; i < vector.length; i++) {
                                        vector[i] = values.get(i).floatValue();
                                    }
                                    return Embedding.from(vector);
                                })
                        .toList());
    }
}
