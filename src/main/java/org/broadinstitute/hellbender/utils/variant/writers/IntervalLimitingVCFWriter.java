package org.broadinstitute.hellbender.utils.variant.writers;

import htsjdk.samtools.util.OverlapDetector;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import org.broadinstitute.hellbender.utils.SimpleInterval;

import java.util.List;

public class IntervalLimitingVCFWriter implements VariantContextWriter {
    private final VariantContextWriter writer;
    private final OverlapDetector<SimpleInterval> detector;

    public IntervalLimitingVCFWriter(final VariantContextWriter writer, List<SimpleInterval> intervals) {
        this.writer = writer;
        detector = OverlapDetector.create(intervals);
    }

    @Override
    public void writeHeader(final VCFHeader header) {
        writer.writeHeader(header);
    }

    @Override
    public void close() {
        writer.close();
    }

    @Override
    public boolean checkError() {
        return writer.checkError();
    }

    @Override
    public void add(final VariantContext vc) {
        final SimpleInterval startPosition = new SimpleInterval(vc.getContig(), vc.getStart(), vc.getStart());
        if(detector.overlapsAny(startPosition)) {
            writer.add(vc);
        }
    }

    @Override
    public void setHeader(final VCFHeader header) {
        writer.setHeader(header);
    }
}
