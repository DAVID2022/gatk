package org.broadinstitute.hellbender.cmdline.argumentcollections;

import org.broadinstitute.hellbender.engine.GATKInputPath;

import java.io.Serializable;
import java.nio.file.Path;

/**
 * An abstract ArgumentCollection for specifying a reference sequence file
 */
public abstract class ReferenceInputArgumentCollection implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Get the name of the reference input specified at the command line.
     */
    public abstract GATKInputPath getReferenceInputPath();

    // TODO: get rid of this
    /**
     * Get the name of the reference file specified at the command line.
     */
    public String getReferenceFileName() {
        final GATKInputPath inputPath = getReferenceInputPath();
        return inputPath == null ? null : getReferenceInputPath().toString();
    }

    /**
     * Get the Path to the reference, may be null
     */
    public Path getReferencePath() {
        return getReferenceInputPath() != null ? getReferenceInputPath().toPath() : null;
    }
}
