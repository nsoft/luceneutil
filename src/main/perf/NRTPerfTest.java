package perf;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene46.Lucene46Codec;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NRTCachingDirectory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Version;

import perf.IndexThreads.Mode;

// cd /a/lucene/trunk/checkout
// ln -s /path/to/lucene/util/perf .
// ant compile; javac -cp ../modules/analysis/build/common/classes/java:build/classes/java:build/classes/test perf/NRTPerfTest.java perf/LineFileDocs.java
// java -Xmx2g -Xms2g -server -Xbatch -cp .:lib/junit-4.7.jar:../modules/analysis/build/common/classes/java:build/classes/java:build/classes/test perf.NRTPerfTest MMapDirectory /p/lucene/indices/wikimedium.clean.svn.Standard.nd10M/index multi /lucene/data/enwiki-20110115-lines-1k-fixed.txt 17 1000 1000 2 1 1 update 1 no

// TODO
//   - maybe target certain MB/sec update rate...?
//   - hmm: we really should have a separate line file, shuffled, that holds the IDs for each line; this way we can update doc w/ same original doc and then we can assert hit counts match
//   - share *Task code from SearchPerfTest
//   - cutover to SearcherManager/NRTManager

public class NRTPerfTest {

  public static class SearchThread extends Thread {
    private final double runTimeSec;
    private final SearcherManager manager;

    public SearchThread(SearcherManager manager, double runTimeSec) {
    	this.manager = manager;
      this.runTimeSec = runTimeSec;
    }

    @Override
    public void run() {
      try {
        final long startNS = System.nanoTime();
				final long stopNS = startNS + (long) (runTimeSec * 1000000000);
				while (true) {
          if (System.nanoTime() >= stopNS) {
            break;
          }
					for (int queryIdx = 0; queryIdx < queries.length; queryIdx++) {
            final Query query = queries[queryIdx];
            IndexSearcher s = manager.acquire();
            try {
            	s.search(query, 10);
//              final int hitCount = s.search(query, 10).totalHits;
              // Not until we have shuffled line docs file w/ matching IDs
              //if (queryHitCounts != null && hitCount != queryHitCounts[queryIdx]) {
              //throw new RuntimeException("hit counts differ for query=" + query + " expected=" + queryHitCounts[queryIdx] + " actual=" + hitCount);
              //}
            } finally {
            	manager.release(s);
            }
            searchesByTime[currentQT.get()].incrementAndGet();
          }
          // Burn: no pause
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  static final AtomicInteger currentQT = new AtomicInteger();
  static AtomicInteger[] docsIndexedByTime;
  static AtomicInteger[] searchesByTime;
  static AtomicLong[] totalUpdateTimeByTime; 
  static int statsEverySec;

  static Query[] queries;
  //static int[] queryHitCounts;                    // only verified if update mode

  public static void main(String[] args) throws Exception {

    final String dirImpl = args[0];
    final String dirPath = args[1];
    final String commit = args[2];
    final String lineDocFile = args[3];
    final long seed = Long.parseLong(args[4]);
    final double docsPerSec = Double.parseDouble(args[5]);
    final double runTimeSec = Double.parseDouble(args[6]);
    final int numSearchThreads = Integer.parseInt(args[7]);
    final int numIndexThreads = Integer.parseInt(args[8]);
    final double reopenPerSec = Double.parseDouble(args[9]);
    final Mode mode = Mode.valueOf(args[10].toUpperCase(Locale.ROOT));
    statsEverySec = Integer.parseInt(args[11]);
    final boolean doCommit = args[12].equals("yes");
    final double mergeMaxWriteMBPerSec = Double.parseDouble(args[13]);
    if (mergeMaxWriteMBPerSec != 0.0) {
      throw new IllegalArgumentException("mergeMaxWriteMBPerSec must be 0.0 until LUCENE-3202 is done");
    }

    final boolean hasProcMemInfo = new File("/proc/meminfo").exists();

    System.out.println("DIR=" + dirImpl);
    System.out.println("Index=" + dirPath);
    System.out.println("Commit=" + commit);
    System.out.println("LineDocs=" + lineDocFile);
    System.out.println("Docs/sec=" + docsPerSec);
    System.out.println("Run time sec=" + runTimeSec);
    System.out.println("NumSearchThreads=" + numSearchThreads);
    System.out.println("NumIndexThreads=" + numIndexThreads);
    System.out.println("Reopen/sec=" + reopenPerSec);
    System.out.println("Mode=" + mode);

    System.out.println("Record stats every " + statsEverySec + " seconds");
    final int count = (int) ((runTimeSec / statsEverySec) + 2);
    docsIndexedByTime = new AtomicInteger[count];
    searchesByTime = new AtomicInteger[count];
    totalUpdateTimeByTime = new AtomicLong[count];
    final AtomicInteger reopensByTime[] = new AtomicInteger[count];
    for(int i=0;i<count;i++) {
      docsIndexedByTime[i] = new AtomicInteger();
      searchesByTime[i] = new AtomicInteger();
      totalUpdateTimeByTime[i] = new AtomicLong();
      reopensByTime[i] = new AtomicInteger();
    }

    System.out.println("Max merge MB/sec = " + (mergeMaxWriteMBPerSec <= 0.0 ? "unlimited" : mergeMaxWriteMBPerSec));
    final Random random = new Random(seed);
    
    final LineFileDocs docs = new LineFileDocs(lineDocFile, true, false, false, false, false, null, new HashSet<String>(), null, true);

    final Directory dir0;
    if (dirImpl.equals("MMapDirectory")) {
      dir0 = new MMapDirectory(new File(dirPath));
    } else if (dirImpl.equals("NIOFSDirectory")) {
      dir0 = new NIOFSDirectory(new File(dirPath));
    } else if (dirImpl.equals("SimpleFSDirectory")) {
      dir0 = new SimpleFSDirectory(new File(dirPath));
    } else {
    	docs.close();
      throw new RuntimeException("unknown directory impl \"" + dirImpl + "\"");
    }
    //final NRTCachingDirectory dir = new NRTCachingDirectory(dir0, 10, 200.0, mergeMaxWriteMBPerSec);
    final NRTCachingDirectory dir = new NRTCachingDirectory(dir0, 20, 400.0);
    //final MergeScheduler ms = dir.getMergeScheduler();
    //final Directory dir = dir0;
    //final MergeScheduler ms = new ConcurrentMergeScheduler();

    queries = new Query[1];
		for (int idx = 0; idx < queries.length; idx++) {
      queries[idx] = new TermQuery(new Term("body", "10"));
      /*
      BooleanQuery bq = new BooleanQuery();
      bq.add(new TermQuery(new Term("body", "10")),
             BooleanClause.Occur.SHOULD);
      bq.add(new TermQuery(new Term("body", "11")),
             BooleanClause.Occur.SHOULD);
      queries[idx] = bq;
      */
    }

    // Open an IW on the requested commit point, but, don't
    // delete other (past or future) commit points:
    final IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_50, new StandardAnalyzer(Version.LUCENE_50))
      .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE).setRAMBufferSizeMB(256.0);
    //iwc.setMergeScheduler(ms);

    final Codec codec = new Lucene46Codec() {
      @Override
      public PostingsFormat getPostingsFormatForField(String field) {
        if (field.equals("id")) {
          return PostingsFormat.forName("Memory");
        } else {
          return PostingsFormat.forName("Lucene41");
        }
      }
      
      private final DocValuesFormat direct = DocValuesFormat.forName("Direct");
//      private final DocValuesFormat sparse = DocValuesFormat.forName("Lucene45Sparse");
      @Override
      public DocValuesFormat getDocValuesFormatForField(String field) {
      	return direct;
      }
    };

    iwc.setCodec(codec);

    /*
    iwc.setMergePolicy(new LogByteSizeMergePolicy());
    ((LogMergePolicy) iwc.getMergePolicy()).setUseCompoundFile(false);
    ((LogMergePolicy) iwc.getMergePolicy()).setMergeFactor(30);
    ((LogByteSizeMergePolicy) iwc.getMergePolicy()).setMaxMergeMB(10000.0);
    System.out.println("USING LOG BS MP");
    */
    
    TieredMergePolicy tmp = new TieredMergePolicy();
    tmp.setNoCFSRatio(0.0);
    tmp.setMaxMergedSegmentMB(1000000.0);
    //tmp.setReclaimDeletesWeight(3.0);
    //tmp.setMaxMergedSegmentMB(7000.0);
    iwc.setMergePolicy(tmp);

    if (!commit.equals("none")) {
      iwc.setIndexCommit(PerfUtils.findCommitPoint(commit, dir));
    }

    // Make sure merges run @ higher prio than indexing:
    final ConcurrentMergeScheduler cms = (ConcurrentMergeScheduler) iwc.getMergeScheduler();
    cms.setMergeThreadPriority(Thread.currentThread().getPriority()+2);
    cms.setMaxMergesAndThreads(4, 1);

    iwc.setMergedSegmentWarmer(new IndexWriter.IndexReaderWarmer() {
        @Override
        public void warm(AtomicReader reader) throws IOException {
          final long t0 = System.currentTimeMillis();
          //System.out.println("DO WARM: " + reader);
          IndexSearcher s = new IndexSearcher(reader);
          for(Query query : queries) {
            s.search(query, 10);
          }

          // Warm terms dict & index:
          /*
          final TermsEnum te = reader.fields().terms("body").iterator();
          long sumDocs = 0;
          DocsEnum docs = null;
          int counter = 0;
          final List<BytesRef> terms = new ArrayList<BytesRef>();
          while(te.next() != null) {
            docs = te.docs(null, docs);
            if (counter++ % 50 == 0) {
              terms.add(new BytesRef(te.term()));
            }
            int docID;
            while((docID = docs.nextDoc()) != DocsEnum.NO_MORE_DOCS) {
              sumDocs += docID;
            }
          }
          Collections.reverse(terms);

          System.out.println("warm: " + terms.size() + " terms");
          for(BytesRef term : terms) {
            sumDocs += reader.docFreq("body", term);
          }
          */
          final long t1 = System.currentTimeMillis();
          System.out.println("warm took " + (t1-t0) + " msec");

          //NativePosixUtil.mlockTermsDict(reader, "id");
        }
      });

    final IndexWriter w = new IndexWriter(dir, iwc);
    //w.setInfoStream(System.out);

    IndexThreads.UpdatesListener updatesListener = new IndexThreads.UpdatesListener() {
    	long startTimeNS;
			@Override
			public void beforeUpdate() {
				startTimeNS = System.nanoTime();
			}
			@Override
			public void afterUpdate() {
        int idx = currentQT.get();
        totalUpdateTimeByTime[idx].addAndGet(System.nanoTime() - startTimeNS);
        docsIndexedByTime[idx].incrementAndGet();
			}
		};
		IndexThreads indexThreads = new IndexThreads(random, w, docs, numIndexThreads, -1, false, false, mode, (float) (docsPerSec/numIndexThreads), updatesListener);
    indexThreads.start();

    // NativePosixUtil.mlockTermsDict(startR, "id");
    final SearcherManager manager = new SearcherManager(w, true, null);
    IndexSearcher s = manager.acquire();
    try {
    	System.out.println("Reader=" + s.getIndexReader());
		} finally {
			manager.release(s);
		}

    // Cannot do this until we have shuffled line file where IDs match:
    /*
    if (doUpdates) {
      queryHitCounts = new int[queries.length];
      final IndexSearcher s = getSearcher();
      try {
        for(int queryIdx=0;queryIdx<queries.length;queryIdx++) {
          queryHitCounts[queryIdx] = s.search(queries[queryIdx], 1).totalHits;
        }
      } finally {
        releaseSearcher(s);
      }
    }
    */

    final SearchThread[] searchThreads = new SearchThread[numSearchThreads];

		for (int i = 0; i < numSearchThreads; i++) {
      searchThreads[i] = new SearchThread(manager, runTimeSec);
      //System.out.println("SEARCH PRI=" + searchThreads[i].getPriority() + " MIN=" + Thread.MIN_PRIORITY + " MAX=" + Thread.MAX_PRIORITY);
      searchThreads[i].setName("SearchThread " + i);
      searchThreads[i].start();
    }

    Thread reopenThread = new Thread() {
      @Override
      public void run() {
        try {
          final long startMS = System.currentTimeMillis();
          final long stopMS = startMS + (long) (runTimeSec * 1000);

          int reopenCount = 1;
          while (true) {
            final long t = System.currentTimeMillis();
            if (t >= stopMS) {
              break;
            }
            
            final long sleepMS = startMS + (long) (1000*(reopenCount/reopenPerSec)) - System.currentTimeMillis();
            if (sleepMS < 0) {
            	System.out.println("WARNING: reopen fell behind by " + Math.abs(sleepMS) + " ms");
            } else {
            	Thread.sleep(sleepMS);
            }

            IndexSearcher curS = manager.acquire();
            try {
            	final long tStart = System.nanoTime();
            	manager.maybeRefresh();
            	IndexSearcher newS = manager.acquire();
            	try {
            		if (curS != newS) {
            			System.out.println("Reopen: " + String.format("%9.4f", (System.nanoTime() - tStart)/1000000.0) + " msec");
            			++reopenCount;
            			reopensByTime[currentQT.get()].incrementAndGet();
            		} else {
            			System.out.println("WARNING: no changes on reopen");
            		}
							} finally {
								manager.release(newS);
							}
						} finally {
							manager.release(curS);
						}
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    reopenThread.setName("ReopenThread");
    reopenThread.setPriority(4+Thread.currentThread().getPriority());
    System.out.println("REOPEN PRI " + reopenThread.getPriority());
    reopenThread.start();

    Thread.currentThread().setPriority(5+Thread.currentThread().getPriority());
    System.out.println("TIMER PRI " + Thread.currentThread().getPriority());

    //System.out.println("KICKING OFF OPTIMIZE!!");
    //w.optimize(false);

    //System.out.println("Start: " + new Date());

    final long startMS = System.currentTimeMillis();
    final long stopMS = startMS + (long) (runTimeSec * 1000);
    int lastQT = -1;
    while(true) {
      final long t = System.currentTimeMillis();
      if (t >= stopMS) {
        break;
      }
      final int qt = (int) ((t-startMS)/statsEverySec/1000);
      currentQT.set(qt);
      if (qt != lastQT) {
        final int prevQT = lastQT;
        lastQT = qt;
        if (prevQT > 0) {
          final String other;
          if (hasProcMemInfo) {
            other = " D=" + getLinuxDirtyBytes();
          } else {
            other = "";
          }
          int prev = prevQT - 1;
          System.out.println(String.format("QT %d searches=%d docs=%d reopens=%s totUpdateTime=%d", 
          										prev, 
          										searchesByTime[prev].get(),
          										docsIndexedByTime[prev].get(),
          										reopensByTime[prev].get() + other,
          										TimeUnit.NANOSECONDS.toMillis(totalUpdateTimeByTime[prev].get())));
        }
      }
      Thread.sleep(25);
    }

    indexThreads.stop();

    for(SearchThread t : searchThreads) {
      t.join();
    }

    reopenThread.join();

    System.out.println("By time:");
    for(int i=0;i<searchesByTime.length-2;i++) {
      System.out.println(String.format("  %d searches=%d docs=%d reopens=%d totUpdateTime=%d", 
      									i*statsEverySec,
      									searchesByTime[i].get(),
      									docsIndexedByTime[i].get(),
      									reopensByTime[i].get(),
      									TimeUnit.NANOSECONDS.toMillis(totalUpdateTimeByTime[i].get())));
    }
    manager.close();
    if (doCommit) {
      w.close(false);
    } else {
      w.rollback();
    }
  }

  private static long getLinuxDirtyBytes() throws Exception {
    final BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"), 4096);
    int dirtyKB = -1;
    try {
      while(true) {
        String line = br.readLine();
        if (line == null) {
          break;
        } else if (line.startsWith("Dirty:")) {
          final String trimmed = line.trim();
          dirtyKB = Integer.parseInt(trimmed.substring(7, trimmed.length()-3).trim());
          break;
        }
      }
    } catch (Exception e) {
      e.printStackTrace(System.out);
    } finally {
      br.close();
    }
    
    return dirtyKB;
  }
}