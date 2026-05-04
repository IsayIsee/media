package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsAdsParser {

  private static final String TAG = HlsAdsParser.class.getSimpleName();

  private static final String TAG_DURATION = "#EXTINF";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String DEFAULT_GROUP_IDENTIFIER = "NO_PATH";
  private static final Pattern REGEX_DURATION = Pattern.compile(TAG_DURATION + ":([\\d.]+)");

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;

  private static final int AD_BREAK_THRESHOLD_SHORT = 3;
  private static final int AD_BREAK_THRESHOLD_MEDIUM = 4;
  private static final int AD_BREAK_THRESHOLD_LONG = 5;
  private static final int AD_BREAK_THRESHOLD_EXTRA = 6;

  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;
  private static final double AD_BLOCK_SIZE_RATIO = 0.75;
  private static final double DURATION_TIER_SHORT = 30.0;
  private static final double DURATION_TIER_MEDIUM = 60.0;
  private static final double DURATION_TIER_LONG = 90.0;

  public static String process(String m3u8) {
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Log.d(TAG, "Executing HlsAdsParser...");
    String[] lines = m3u8.split("\\r?\\n");
    Set<String> adSegments = findAds(lines);
    if (adSegments.isEmpty()) {
      Log.d(TAG, "No ad segments detected. Returning original content.");
      return m3u8;
    } else {
      Log.d(TAG, "Detected " + adSegments.size() + " ad segments to remove. Rebuilding playlist...");
      return rebuildM3u8(lines, adSegments);
    }
  }

  private static Set<String> findAds(String[] lines) {
    Log.d(TAG, "Executing Primary Strategy: Filename/Path Analysis...");
    List<String> allSegments = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (isSegmentLine(trimmedLine)) {
        allSegments.add(trimmedLine);
      }
    }
    Set<String> adsFromFilename = findAdsByFilename(allSegments);
    if (!adsFromFilename.isEmpty()) {
      Log.d(TAG, "Primary strategy successful. Using its result.");
      return adsFromFilename;
    }
    Log.d(TAG, "Primary strategy did not find ads. Falling back to Discontinuity Analysis.");
    return findAdsByDiscontinuity(lines);
  }

  private static Set<String> findAdsByDiscontinuity(String[] lines) {
    List<List<String>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "Discontinuity Analysis: Only " + blocks.size() + " block(s) found. Strategy inconclusive.");
      return Collections.emptySet();
    }
    List<List<String>> analysisBlocks = blocks.subList(0, blocks.size() - 1);
    int modeSize = getModeSize(analysisBlocks);
    if (modeSize <= 0) {
      return Collections.emptySet();
    }
    int minorityBlockCount = 0;
    Set<String> adSegments = new HashSet<>();
    double adSizeThreshold = modeSize * AD_BLOCK_SIZE_RATIO;
    int maxBlockSize = 0;
    for (List<String> block : analysisBlocks) {
      int size = block.size();
      if (size > maxBlockSize) maxBlockSize = size;
      if (size < adSizeThreshold) {
        minorityBlockCount++;
        adSegments.addAll(block);
      }
    }
    if (minorityBlockCount == 0 && modeSize * 2 < maxBlockSize) {
      Log.d(TAG, "Discontinuity Analysis: Mode (" + modeSize + ") is much smaller than max block (" + maxBlockSize + "). Treating mode-sized-or-smaller blocks as ads.");
      for (List<String> block : analysisBlocks) {
        if (block.size() <= modeSize) {
          minorityBlockCount++;
          adSegments.addAll(block);
        }
      }
    }
    double totalDurationMinutes = getTotalDurationInMinutes(lines);
    int minorityCountThreshold = getMinorityCountThreshold(totalDurationMinutes);
    Log.d(TAG, "Total duration is " + String.format(Locale.getDefault(), "%.2f", totalDurationMinutes) + " minutes. Ad block threshold is " + minorityCountThreshold + ".");
    if (minorityBlockCount > 0 && minorityBlockCount <= minorityCountThreshold) {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " ad block(s) (mode=" + modeSize + ", excluding last block). Identified as ads.");
      return adSegments;
    } else {
      Log.d(TAG, "Discontinuity Analysis: Found " + minorityBlockCount + " ad blocks. Count exceeds threshold of " + minorityCountThreshold + " (or is 0). Result is ambiguous, ignoring.");
      return Collections.emptySet();
    }
  }

  private static int getModeSize(List<List<String>> blocks) {
    Map<Integer, Integer> sizeFrequencies = new HashMap<>();
    for (List<String> block : blocks) {
      int size = block.size();
      sizeFrequencies.put(size, sizeFrequencies.containsKey(size) ? sizeFrequencies.get(size) + 1 : 1);
    }
    int modeSize = -1;
    int maxFreq = -1;
    for (Map.Entry<Integer, Integer> entry : sizeFrequencies.entrySet()) {
      int freq = entry.getValue();
      int size = entry.getKey();
      if (freq > maxFreq || (freq == maxFreq && size > modeSize)) {
        maxFreq = freq;
        modeSize = size;
      }
    }
    return modeSize;
  }

  private static double getTotalDurationInMinutes(String[] lines) {
    double totalSeconds = 0.0;
    for (String line : lines) {
      if (line.startsWith(TAG_DURATION)) {
        Matcher matcher = REGEX_DURATION.matcher(line);
        if (matcher.find()) {
          try {
            totalSeconds += Double.parseDouble(matcher.group(1));
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
    return totalSeconds / 60.0;
  }

  private static int getMinorityCountThreshold(double totalMinutes) {
    if (totalMinutes <= DURATION_TIER_SHORT) {
      return AD_BREAK_THRESHOLD_SHORT;
    }
    if (totalMinutes <= DURATION_TIER_MEDIUM) {
      return AD_BREAK_THRESHOLD_MEDIUM;
    }
    if (totalMinutes <= DURATION_TIER_LONG) {
      return AD_BREAK_THRESHOLD_LONG;
    }
    return AD_BREAK_THRESHOLD_EXTRA;
  }

  private static List<List<String>> getDiscontinuityBlocks(String[] lines) {
    List<List<String>> blocks = new ArrayList<>();
    List<String> currentBlock = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (!currentBlock.isEmpty()) {
          blocks.add(currentBlock);
        }
        currentBlock = new ArrayList<>();
      } else if (isSegmentLine(trimmedLine)) {
        currentBlock.add(trimmedLine);
      }
    }
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock);
    }
    return blocks;
  }

  private static Set<String> findAdsByFilename(List<String> allSegments) {
    if (allSegments.size() < 2) {
      return Collections.emptySet();
    }
    Map<String, List<String>> structuralGroups = groupBy(allSegments, HlsAdsParser::getStructuralIdentifier);
    if (structuralGroups.size() > 1 && structuralGroups.size() <= REASONABLE_GROUP_LIMIT) {
      return findMinorityGroup(structuralGroups);
    }
    return findAdsByPrefixAnalysis(allSegments);
  }

  private static String getStructuralIdentifier(String segmentUrl) {
    int schemeEnd = segmentUrl.indexOf("://");
    if (schemeEnd != -1) {
      int hostEnd = segmentUrl.indexOf('/', schemeEnd + 3);
      return hostEnd != -1 ? segmentUrl.substring(0, hostEnd) : segmentUrl;
    }
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    return lastSlashIndex != -1 ? segmentUrl.substring(0, lastSlashIndex) : DEFAULT_GROUP_IDENTIFIER;
  }

  private static Set<String> findMinorityGroup(Map<String, List<String>> groups) {
    int totalSize = 0;
    for (List<String> segments : groups.values()) {
      totalSize += segments.size();
    }
    int maxSize = getMaxGroupSize(groups);
    if (maxSize * 2 <= totalSize) {
      Log.d(TAG, "findMinorityGroup: no clear dominant group (" + maxSize + "/" + totalSize + "). Skipping.");
      return Collections.emptySet();
    }
    Set<String> adSegments = new HashSet<>();
    for (List<String> segments : groups.values()) {
      if (segments.size() < maxSize) {
        adSegments.addAll(segments);
      }
    }
    return adSegments;
  }

  private static Set<String> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == -1) {
      return Collections.emptySet();
    }
    Map<String, List<String>> groups = groupBy(segments, s -> s.length() > optimalPrefixLength ? s.substring(0, optimalPrefixLength) : s);
    if (groups.size() <= 1 || groups.size() > REASONABLE_GROUP_LIMIT) {
      return Collections.emptySet();
    }
    return findMinorityGroup(groups);
  }

  private static int findOptimalPrefixLength(List<String> segments) {
    if (segments.size() < 2) {
      return -1;
    }
    int shortestSegmentLength = Integer.MAX_VALUE;
    for (String segment : segments) {
      if (segment.length() < shortestSegmentLength) {
        shortestSegmentLength = segment.length();
      }
    }
    int bestLength = -1;
    double highestScore = 0.0;
    int maxLength = shortestSegmentLength - SEQUENCE_NUMBER_RESERVED_LENGTH;
    for (int length = MIN_PREFIX_LENGTH_TO_TEST; length < maxLength; length++) {
      final int len = length;
      Map<String, List<String>> groups = groupBy(segments, s -> s.length() > len ? s.substring(0, len) : s);
      int groupCount = groups.size();
      if (groupCount <= 1 || groupCount > REASONABLE_GROUP_LIMIT) {
        continue;
      }
      int maxGroupSize = getMaxGroupSize(groups);
      double score = (double) maxGroupSize / segments.size();
      if (score >= MIN_MAJORITY_GROUP_RATIO && score > highestScore) {
        highestScore = score;
        bestLength = length;
      }
    }
    return bestLength;
  }

  private static Map<String, List<String>> groupBy(List<String> segments, Classifier keyFn) {
    Map<String, List<String>> groups = new HashMap<>();
    for (String segment : segments) {
      String key = keyFn.classify(segment);
      List<String> group = groups.get(key);
      if (group == null) {
        group = new ArrayList<>();
        groups.put(key, group);
      }
      group.add(segment);
    }
    return groups;
  }

  private interface Classifier {
    String classify(String segment);
  }

  private static int getMaxGroupSize(Map<String, List<String>> groups) {
    int maxSize = 0;
    for (List<String> group : groups.values()) {
      if (group.size() > maxSize) maxSize = group.size();
    }
    return maxSize;
  }

  private static boolean isSegmentLine(String trimmedLine) {
    return !trimmedLine.isEmpty() && !trimmedLine.startsWith("#");
  }

  private static String rebuildM3u8(String[] lines, Set<String> adSegments) {
    List<String> stripped = removeAdSegments(lines, adSegments);
    List<String> cleaned = removeOrphanedDiscontinuityTags(stripped);
    StringBuilder builder = new StringBuilder();
    for (String line : cleaned) {
      builder.append(line).append("\n");
    }
    return builder.toString();
  }

  private static List<String> removeAdSegments(String[] lines, Set<String> adSegments) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_DURATION)) {
        int segIndex = findNextSegmentIndex(lines, i + 1);
        if (segIndex < lines.length && adSegments.contains(lines[segIndex].trim())) {
          i = segIndex;
          continue;
        }
      } else if (adSegments.contains(line)) {
        continue;
      }
      result.add(line);
    }
    return result;
  }

  private static List<String> removeOrphanedDiscontinuityTags(List<String> lines) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.equals(TAG_DISCONTINUITY)) {
        boolean prevIsDiscontinuityOrBoundary = (i == 0) || lines.get(i - 1).equals(TAG_DISCONTINUITY);
        String nextLine = (i + 1 < lines.size()) ? lines.get(i + 1) : null;
        boolean nextIsDiscontinuityOrBoundary = nextLine == null || nextLine.equals(TAG_DISCONTINUITY) || nextLine.equals(TAG_ENDLIST);
        if (prevIsDiscontinuityOrBoundary || nextIsDiscontinuityOrBoundary) {
          continue;
        }
      }
      result.add(line);
    }
    return result;
  }

  private static int findNextSegmentIndex(String[] lines, int fromIndex) {
    int i = fromIndex;
    while (i < lines.length && lines[i].trim().startsWith("#")) {
      i++;
    }
    return i;
  }
}
