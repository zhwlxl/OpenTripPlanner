package org.opentripplanner.analyst.scenario;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.onebusaway.gtfs.model.Stop;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.trippattern.FrequencyEntry;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Convert scheduled trips to frequencies. Will partition trips by service day.
 */
public class ConvertToFrequency extends Modification {
    private static final Logger LOG = LoggerFactory.getLogger(ConvertToFrequency.class);

    public List<TripTimes> scheduledTrips = new ArrayList<>();
    public List<FrequencyEntry> frequencyEntries = new ArrayList<>();
    private Multimap<String, TripTimes> tripsToConvert = HashMultimap.create();

    public String[] routeId;

    @Override public String getType() {
        return "convert-to-frequency";
    }

    /** Windows in which to do the conversion, array of int[2] of startTimeSecs, endTimeSecs */
    public int windowStart;

    public int windowEnd;

    /** How to group trips for conversion to frequencies: by route, route and direction, or by trip pattern. */
    public ConversionGroup groupBy;

    public void apply (List<FrequencyEntry> frequencyEntries, List<TripTimes> scheduledTrips, Graph graph, BitSet servicesRunning) {
        // preserve existing frequency entries
        this.frequencyEntries.addAll(frequencyEntries);

        Set<String> routeIds = new HashSet<>();

        if (routeId != null)
            Stream.of(routeId).forEach(routeIds::add);

        // loop over scheduled trips and figure out what to do with them
        for (TripTimes tt : scheduledTrips) {
            if (routeId == null || routeIds.contains(tt.trip.getRoute().getId().getId())) {
                // put this in the appropriate group for frequency conversion
                String key;

                switch (groupBy) {
                case ROUTE_DIRECTION:
                    key = tt.trip.getRoute().getId().getId() + "_" + tt.trip.getDirectionId();
                    break;
                case ROUTE:
                    key = tt.trip.getRoute().getId().getId();
                    break;
                case PATTERN:
                    key = graph.index.patternForTrip.get(tt.trip).getExemplar().getId().getId();
                    break;
                default:
                    throw new RuntimeException("Unrecognized group by value");
                }

                tripsToConvert.put(key, tt);
            } else {
                // don't touch this trip
                this.scheduledTrips.add(tt);
            }
        }

        // loop over all the groups and create frequency entries
        GROUPS: for (Map.Entry<String, Collection<TripTimes>> e: tripsToConvert.asMap().entrySet()) {
            // get just the running services
            List<TripTimes> group = e.getValue().stream()
                    .filter(tt -> servicesRunning.get(tt.serviceCode))
                    .filter(tt -> windowStart < tt.getDepartureTime(0) && tt.getDepartureTime(0) < windowEnd)
                    .collect(Collectors.toList());

            if (group.isEmpty())
                continue GROUPS;

            if (group.size() == 1) {
                group.stream().forEach(scheduledTrips::add);
                continue GROUPS;
            }

            // find the dominant pattern
            TObjectIntMap<TripPattern> patternCount = new TObjectIntHashMap<>(5, 0.75f, 0);
            group.forEach(tt -> patternCount.adjustOrPutValue(graph.index.patternForTrip.get(tt.trip), 1, 1));

            int maxCount = 0;
            TripPattern tripPattern = null;

            for (TObjectIntIterator<TripPattern> it = patternCount.iterator(); it.hasNext();) {
                it.advance();
                if (it.value() > maxCount) {
                    maxCount = it.value();
                    tripPattern = it.key();
                }
            }

            // find a stop that is common to all trip patterns
            Set<Stop> stops = new HashSet<>(tripPattern.getStops());
            patternCount.keySet().stream().forEach(p -> stops.retainAll(p.getStops()));

            if (stops.isEmpty()) {
                LOG.warn("Unable to find common stop for key {}, not converting to frequencies", e.getKey());
                scheduledTrips.addAll(e.getValue());
                continue GROUPS;
            }

            Stop stop = stops.stream().findFirst().get();

            // determine the median frequency at this stop
            TIntList arrivalTimes = new TIntArrayList();

            for (boolean filter : new boolean[] { true, false }) {
                for (TripTimes tt : group) {
                    TripPattern tp = graph.index.patternForTrip.get(tt.trip);
                    int arrivalTime = tt.getArrivalTime(tp.getStops().indexOf(stop));

                    // filter again so we don't have issues where one pattern has gone out of the time window but a longer
                    // one hasn't (i.e. there are two patterns, one has the common stop an hour past the start and the other
                    // five minutes after the start. The longer pattern will have times past the window, the
                    // shorter will not.
                    // however, if we apply the filter and end up with no trips at this stop, re-run with the filter disabled
                    if (windowStart < arrivalTime && arrivalTime < windowEnd || !filter)
                        arrivalTimes.add(arrivalTime);
                }

                // if we didn't find stops, continue, which will turn off the filter
                if (arrivalTimes.size() > 1)
                    break;
            }

            // now convert to elapsed times
            arrivalTimes.sort();
            int[] headway = new int[arrivalTimes.size() - 1];
            for (int i = 1; i < arrivalTimes.size(); i++) {
                headway[i - 1] = arrivalTimes.get(i) - arrivalTimes.get(i - 1);
            }

            // now get the median
            // we use the median not the mean in case there are reliever runs where one bus comes just
            // a moment after the one before it (common on sharply peaked routes, e.g. those serving
            // schools)
            Arrays.sort(headway);

            // median time between vehicles, seconds
            int median;

            if (headway.length == 1)
                median = headway[0];
            else if (headway.length % 2 == 0)
                median = (headway[(headway.length - 1) / 2] + headway[(headway.length - 1) / 2 + 1]) / 2;
            else
                median = headway[(headway.length - 1) / 2];

            LOG.info("Headway for route {} ({}) in direction {}: {}min", tripPattern.route.getShortName(), tripPattern.route.getId().getId(), tripPattern.directionId, median / 60);

            // figure out running/dwell times based on the trips on this pattern
            final TripPattern chosenTp = tripPattern;
            List<TripTimes> candidates = group.stream()
                    .filter(tt -> graph.index.patternForTrip.get(tt.trip) == chosenTp)
                    .collect(Collectors.toList());

            // transposed from what you'd expect: stops on the rows
            int[][] hopTimes = new int[tripPattern.getStops().size() - 1][candidates.size()];
            int[][] dwellTimes = new int[tripPattern.getStops().size()][candidates.size()];

            int tripIndex = 0;
            for (TripTimes tt : candidates) {
                for (int stopIndex = 0; stopIndex < tripPattern.getStops().size(); stopIndex++) {
                    dwellTimes[stopIndex][tripIndex] = tt.getDwellTime(stopIndex);

                    if (stopIndex > 0)
                        hopTimes[stopIndex - 1][tripIndex] = tt.getArrivalTime(stopIndex) - tt.getDepartureTime(stopIndex - 1);
                }
                tripIndex++;
            }

            // collapse it down
            int[] meanHopTimes = new int[tripPattern.getStops().size() - 1];

            int hopIndex = 0;
            for (int[] hop : hopTimes) {
                meanHopTimes[hopIndex++] = IntStream.of(hop).sum() / hop.length;
            }

            int[] meanDwellTimes = new int[tripPattern.getStops().size()];

            int dwellIndex = 0;
            for (int[] dwell : dwellTimes) {
                meanDwellTimes[dwellIndex++] = IntStream.of(dwell).sum() / dwell.length;
            }

            // phew! now let's make a frequency entry
            TripTimes tt = new TripTimes(candidates.get(0));

            int cumulative = 0;
            for (int i = 0; i < tt.getNumStops(); i++) {
                tt.updateArrivalTime(i, cumulative);
                cumulative += meanDwellTimes[i];
                tt.updateDepartureTime(i, cumulative);

                if (i + 1 < tt.getNumStops())
                    cumulative += meanHopTimes[i];
            }

            FrequencyEntry fe = new FrequencyEntry(windowStart - 60 * 60 * 3, windowEnd, median, false, tt);
            this.frequencyEntries.add(fe);
        }
    }

    public static enum ConversionGroup {
        ROUTE_DIRECTION, ROUTE, PATTERN;
    }
}