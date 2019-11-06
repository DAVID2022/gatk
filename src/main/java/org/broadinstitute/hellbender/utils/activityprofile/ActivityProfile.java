package org.broadinstitute.hellbender.utils.activityprofile;

import htsjdk.samtools.SAMFileHeader;
import org.broadinstitute.hellbender.engine.AssemblyRegion;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.*;

/**
 * Class holding information about per-base activity scores for
 * assembly region traversal
 */
public class ActivityProfile {
    protected final List<ActivityProfileState> stateList;

    private final int maxProbPropagationDistance;
    private final double activeProbThreshold;

    protected SimpleInterval regionStartLoc = null;
    private SimpleInterval regionStopLoc = null;

    private SAMFileHeader samHeader;

    /**
     * Create a new empty ActivityProfile
     * @param maxProbPropagationDistance region probability propagation distance beyond its maximum size
     * @param activeProbThreshold threshold for the probability of an activity profile state being active
     */
    public ActivityProfile(final int maxProbPropagationDistance, final double activeProbThreshold, final SAMFileHeader header) {
        stateList = new ArrayList<>();
        this.maxProbPropagationDistance = maxProbPropagationDistance;
        this.activeProbThreshold = activeProbThreshold;
        this.samHeader = header;
    }

    @Override
    public String toString() {
        return String.format("ActivityProfile{start=%s, stop=%s}", regionStartLoc, regionStopLoc);
    }

    /**
     * How far away can probability mass be moved around in this profile?
     *
     * This distance puts an upper limit on how far, in bp, we will ever propagate probability mass around
     * when adding a new ActivityProfileState.  For example, if the value of this function is
     * 10, and you are looking at a state at bp 5, and we know that no states beyond 5 + 10 will have
     * their probability propagated back to that state.
     *
     * @return a positive integer distance in bp
     */
    public int getMaxProbPropagationDistance() {
        return maxProbPropagationDistance;
    }

    /**
     * How many profile results are in this profile?
     * @return the number of profile results
     */
    public int size() {
        return stateList.size();
    }

    /**
     * Is this profile empty? (ie., does it contain no ActivityProfileStates?)
     * @return true if the profile is empty (ie., contains no ActivityProfileStates)
     */
    public boolean isEmpty() {
        return stateList.isEmpty();
    }

    /**
     * Get the span of this activity profile, which is from the start of the first state to the stop of the last
     * @return a potentially null SimpleInterval.  Will be null if this profile is empty
     */
    public SimpleInterval getSpan() {
        return isEmpty() ? null : regionStartLoc.spanWith(regionStopLoc);
    }

    public int getEnd() {
        return regionStopLoc.getEnd();
    }

    // --------------------------------------------------------------------------------
    //
    // routines to add states to a profile
    //
    // --------------------------------------------------------------------------------

    /**
     * Add the next ActivityProfileState to this profile.
     *
     * Must be contiguous with the previously added result, or an IllegalArgumentException will be thrown
     *
     * @param state a well-formed ActivityProfileState result to incorporate into this profile
     */
    public void add(final ActivityProfileState state) {
        Utils.nonNull(state);
        final SimpleInterval loc = state.getLoc();

        if ( regionStartLoc == null ) {
            regionStartLoc = loc;
            regionStopLoc = loc;
        } else {
            Utils.validateArg( regionStopLoc.getStart() == loc.getStart() - 1, () ->
                    "Bad add call to ActivityProfile: loc " + loc + " not immediately after last loc " + regionStopLoc);
            regionStopLoc = loc;
        }

        stateList.add(state);
    }

    // --------------------------------------------------------------------------------
    //
    // routines to get active regions from the profile
    //
    // --------------------------------------------------------------------------------

    /**
     * Get the next completed assembly regions from this profile, and remove all states supporting them from this profile
     *
     * Takes the current profile and finds all of the active / inactive from the start of the profile that are
     * ready.  By ready we mean unable to have their probability modified any longer by future additions to the
     * profile.  The regions that are popped off the profile take their states with them, so the start of this
     * profile will always be after the end of the last region returned here.
     *
     * The regions are returned sorted by genomic position.
     *
     * This function may not return anything in the list, if no regions are ready
     *
     * No returned region will be larger than maxRegionSize.
     *
     * @param assemblyRegionExtension the extension value to provide to the constructed regions
     * @param minRegionSize the minimum region size, in the case where we have to cut up regions that are too large
     * @param maxRegionSize the maximize size of the returned region
     * @param forceConversion if true, we'll return a region whose end isn't sufficiently far from the end of the
     *                        stateList.  Used to close out the active region when we've hit some kind of end (such
     *                        as the end of the contig)
     * @return a non-null list of active regions
     */
    public List<AssemblyRegion> popReadyAssemblyRegions( final int assemblyRegionExtension, final int minRegionSize, final int maxRegionSize, final boolean forceConversion ) {
        Utils.validateArg(assemblyRegionExtension >= 0, () -> "assemblyRegionExtension must be >= 0 but got " + assemblyRegionExtension);
        Utils.validateArg( minRegionSize > 0, () -> "minRegionSize must be >= 1 but got " + minRegionSize);
        Utils.validateArg( maxRegionSize > 0, () -> "maxRegionSize must be >= 1 but got " + maxRegionSize);

        final List<AssemblyRegion> regions = new ArrayList<>();

        while ( true ) {
            final AssemblyRegion nextRegion = popNextReadyAssemblyRegion(assemblyRegionExtension, minRegionSize, maxRegionSize, forceConversion);
            if ( nextRegion == null ) {
                return regions;
            }
            else {
                regions.add(nextRegion);
            }
        }
    }

    /**
     * Helper function for popReadyActiveRegions that pops the first ready region off the front of this profile
     *
     * If a region is returned, modifies the state of this profile so that states used to make the region are
     * no longer part of the profile.  Associated information (like the region start position) of this profile
     * are also updated.
     *
     * @param assemblyRegionExtension the extension value to provide to the constructed regions
     * @param minRegionSize the minimum region size, in the case where we have to cut up regions that are too large
     * @param maxRegionSize the maximize size of the returned region
     * @param forceConversion if true, we'll return a region whose end isn't sufficiently far from the end of the
     *                        stateList.  Used to close out the active region when we've hit some kind of end (such
     *                        as the end of the contig)
     * @return a fully formed assembly region, or null if none can be made
     */
    private AssemblyRegion popNextReadyAssemblyRegion( final int assemblyRegionExtension, final int minRegionSize, final int maxRegionSize, final boolean forceConversion ) {
        if ( stateList.isEmpty() ) {
            return null;
        }

        // If we are flushing the activity profile we need to trim off the excess states so that we don't create regions outside of our current processing interval
        if( forceConversion ) {
            final List<ActivityProfileState> statesToTrimAway = new ArrayList<>(stateList.subList(getSpan().size(), stateList.size()));
            stateList.removeAll(statesToTrimAway);
        }

        final ActivityProfileState first = stateList.get(0);
        final boolean isActiveRegion = first.getActiveProb() > activeProbThreshold;
        final int offsetOfNextRegionEnd = findEndOfRegion(isActiveRegion, minRegionSize, maxRegionSize, forceConversion);
        if ( offsetOfNextRegionEnd == -1 ) {
            // couldn't find a valid ending offset, so we return null
            return null;
        }

        // we need to create the active region, and clip out the states we're extracting from this profile
        final List<ActivityProfileState> sub = stateList.subList(0, offsetOfNextRegionEnd + 1);
        final List<ActivityProfileState> supportingStates = new ArrayList<>(sub);
        sub.clear();

        // update the start and stop locations as necessary
        if ( stateList.isEmpty() ) {
            regionStartLoc = regionStopLoc = null;
        } else {
            regionStartLoc = stateList.get(0).getLoc();
        }
        final SimpleInterval regionLoc = new SimpleInterval(first.getLoc().getContig(), first.getLoc().getStart(), first.getLoc().getStart() + offsetOfNextRegionEnd);
        return new AssemblyRegion(regionLoc, supportingStates, isActiveRegion, assemblyRegionExtension, samHeader);
    }

    /**
     * Find the end of the current region, returning the index into the element isActive element, or -1 if the region isn't done
     *
     * The current region is defined from the start of the stateList, looking for elements that have the same isActiveRegion
     * flag (i.e., if isActiveRegion is true we are looking for states with isActiveProb > threshold, or alternatively
     * for states < threshold).  The maximize size of the returned region is maxRegionSize.  If forceConversion is
     * true, then we'll return the region end even if this isn't safely beyond the max prob propagation distance.
     *
     * Note that if isActiveRegion is true, and we can construct an assembly region > maxRegionSize in bp, we
     * find the further local minimum within that max region, and cut the region there, under the constraint
     * that the resulting region must be at least minRegionSize in bp.
     *
     * @param isActiveRegion is the region we're looking for an active region or inactive region?
     * @param minRegionSize the minimum region size, in the case where we have to cut up regions that are too large
     * @param maxRegionSize the maximize size of the returned region
     * @param forceConversion if true, we'll return a region whose end isn't sufficiently far from the end of the
     *                        stateList.  Used to close out the assembly region when we've hit some kind of end (such
     *                        as the end of the contig)
     * @return the index into stateList of the last element of this region, or -1 if it cannot be found
     */
    private int findEndOfRegion(final boolean isActiveRegion, final int minRegionSize, final int maxRegionSize, final boolean forceConversion) {
        if ( ! forceConversion && stateList.size() < maxRegionSize + getMaxProbPropagationDistance() ) {
            // we really haven't finalized at the probability mass that might affect our decision, so keep
            // waiting until we do before we try to make any decisions
            return -1;
        }

        int endOfActiveRegion = findFirstActivityBoundary(isActiveRegion, maxRegionSize);

        if ( isActiveRegion && endOfActiveRegion == maxRegionSize ) {
            // we've run to the end of the region, let's find a good place to cut
            endOfActiveRegion = findBestCutSite(endOfActiveRegion, minRegionSize);
        }

        // we're one past the end, so i must be decremented
        return endOfActiveRegion - 1;
    }

    /**
     * Find the the local minimum within 0 - endOfActiveRegion where we should divide region
     *
     * This algorithm finds the global minimum probability state within the region [minRegionSize, endOfActiveRegion)
     * (exclusive of endOfActiveRegion), and returns the state index of that state.
     * that it
     *
     * @param endOfActiveRegion the last state of the current active region (exclusive)
     * @param minRegionSize the minimum of the left-most region, after cutting
     * @return the index of state after the cut site (just like endOfActiveRegion)
     */
    private int findBestCutSite(final int endOfActiveRegion, final int minRegionSize) {
        Utils.validateArg(endOfActiveRegion >= minRegionSize, "endOfActiveRegion must be >= minRegionSize");
        Utils.validateArg(minRegionSize >= 0, "minRegionSize must be >= 0");

        int minI = endOfActiveRegion - 1;
        double minP = Double.MAX_VALUE;

        for ( int i = minI; i >= minRegionSize - 1; i-- ) {
            double cur = getProb(i);
            if ( cur < minP && isMinimum(i) ) {
                minP = cur;
                minI = i;
            }
        }

        return minI + 1;
    }

    /**
     * Find the first index into the state list where the state is considered ! isActiveRegion
     *
     * Note that each state has a probability of being active, and this function thresholds that
     * value on activeProbThreshold, coloring each state as active or inactive.  Finds the
     * largest contiguous stretch of states starting at the first state (index 0) with the same isActive
     * state as isActiveRegion.  If the entire state list has the same isActive value, then returns
     * maxRegionSize
     *
     * @param isActiveRegion are we looking for a stretch of active states, or inactive ones?
     * @param maxRegionSize don't look for a boundary that would yield a region of size > maxRegionSize
     * @return the index of the first state in the state list with isActive value != isActiveRegion, or maxRegionSize
     *         if no such element exists
     */
    private int findFirstActivityBoundary(final boolean isActiveRegion, final int maxRegionSize) {
        Utils.validateArg(maxRegionSize > 0, "maxRegionSize must be > 0");

        final int nStates = stateList.size();
        int endOfActiveRegion = 0;

        while ( endOfActiveRegion < nStates && endOfActiveRegion < maxRegionSize ) {
            if ( getProb(endOfActiveRegion) > activeProbThreshold != isActiveRegion ) {
                break;
            }
            endOfActiveRegion++;
        }

        return endOfActiveRegion;
    }

    /**
     * Helper function to get the probability of the state at offset index
     * @param index a valid offset into the state list
     * @return the isActiveProb of the state at index
     */
    private double getProb(final int index) {
        Utils.validIndex(index, stateList.size());

        return stateList.get(index).getActiveProb();
    }

    /**
     * Is the probability at index in a local minimum?
     *
     * Checks that the probability at index is <= both the probabilities to either side.
     * Returns false if index is at the end or the start of the state list.
     *
     * @param index the index of the state we want to test
     * @return true if prob at state is a minimum, false otherwise
     */
    private boolean isMinimum(final int index) {
        Utils.validIndex(index, stateList.size());

        if ( index == stateList.size() - 1 ) {
            // we cannot be at a minimum if the current position is the last in the state list
            return false;
        }
        else if ( index < 1 ) {
            // we cannot be at a minimum if the current position is the first or second
            return false;
        }
        else {
            final double indexP = getProb(index);
            return indexP <= getProb(index+1) && indexP < getProb(index-1);
        }
    }
}
