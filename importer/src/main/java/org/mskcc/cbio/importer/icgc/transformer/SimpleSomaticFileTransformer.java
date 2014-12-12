/*
 *  Copyright (c) 2014 Memorial Sloan-Kettering Cancer Center.
 * 
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY, WITHOUT EVEN THE IMPLIED WARRANTY OF
 *  MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  The software and
 *  documentation provided hereunder is on an "as is" basis, and
 *  Memorial Sloan-Kettering Cancer Center 
 *  has no obligations to provide maintenance, support,
 *  updates, enhancements or modifications.  In no event shall
 *  Memorial Sloan-Kettering Cancer Center
 *  be liable to any party for direct, indirect, special,
 *  incidental or consequential damages, including lost profits, arising
 *  out of the use of this software and its documentation, even if
 *  Memorial Sloan-Kettering Cancer Center 
 *  has been advised of the possibility of such damage.
 */
package org.mskcc.cbio.importer.icgc.transformer;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.FluentIterable;
import com.google.common.hash.BloomFilter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.log4j.Logger;
import org.mskcc.cbio.importer.icgc.support.IcgcSimpleSomaticRecord;
import org.mskcc.cbio.importer.icgc.support.IcgcSimpleSomaticRecordFunnel;
import org.mskcc.cbio.importer.persistence.staging.TsvStagingFileHandler;
import org.mskcc.cbio.importer.persistence.staging.mutation.MutationFileHandlerImpl;
import org.mskcc.cbio.importer.persistence.staging.mutation.MutationModel;
import org.mskcc.cbio.importer.persistence.staging.mutation.MutationTransformer;
import org.mskcc.cbio.importer.persistence.staging.util.StagingUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SimpleSomaticFileTransformer extends MutationTransformer implements IcgcFileTransformer {

    private static final Logger logger = Logger.getLogger(SimpleSomaticFileTransformer.class);

    private Path icgcFilePath;
    private BloomFilter<IcgcSimpleSomaticRecord> icgcRecordFilter;

    public SimpleSomaticFileTransformer(TsvStagingFileHandler aHandler, Path stagingFileDirectory) {
        super(aHandler);
        Preconditions.checkArgument(null != stagingFileDirectory,
                "A Path to a staging file directory is required");
        try {
            Files.createDirectories(stagingFileDirectory);
            aHandler.registerTsvStagingFile(stagingFileDirectory.resolve("data_mutations_extended.txt"),
                    MutationModel.resolveColumnNames());
        } catch (IOException e) {
            logger.error(e);
        }

    }

    @Override
    public void setIcgcFilePath(final Path aPath) {
        if (StagingUtils.isValidInputFilePath(aPath)) {
            this.icgcFilePath = aPath;
            logger.info("Input path = " + this.icgcFilePath.toString());
        }
    }

    private void processSimpleSomaticData() {
        // maintain a collection of the last 200 valid MAF entries using an EvictingQueue to evaluate
        // possible false positives from the Bloom Filter
        final EvictingQueue<IcgcSimpleSomaticRecord> mafQueue = EvictingQueue.create(200);

        try (BufferedReader reader = Files.newBufferedReader(this.icgcFilePath, Charset.defaultCharset());) {

            // generate a new Bloom Filter
            this.icgcRecordFilter = BloomFilter.create(IcgcSimpleSomaticRecordFunnel.INSTANCE, 5000000);
            final CSVParser parser = new CSVParser(reader, CSVFormat.TDF.withHeader());
            /*
            process the uncompressed TSV file downloaded from ICGC
                -filter out duplicate records
                -transform the simple somatic ICGC attributes to MAF records
                - write out the MAF file
            */

            List<SimpleSomaticModel> somaticList = FluentIterable.from(parser)
                    .filter(new Predicate<CSVRecord>() {
                        @Override
                        public boolean apply(CSVRecord record) {
                            final IcgcSimpleSomaticRecord icgcRecord = new IcgcSimpleSomaticRecord(record.toMap());
                            return (!icgcRecordFilter.mightContain(icgcRecord)
                                    || !mafQueue.contains(icgcRecord));
                        }

                    })
                            //
                            // use a filter to update the BloomFilter and maf queue contents
                    .filter(new Predicate<CSVRecord>() {
                        @Override
                        public boolean apply(CSVRecord record) {
                            final IcgcSimpleSomaticRecord icgcRecord = new IcgcSimpleSomaticRecord(record.toMap());
                            mafQueue.add(icgcRecord);
                            icgcRecordFilter.put(icgcRecord);
                            return true;
                        }
                    })
                    .transform(new Function<CSVRecord, SimpleSomaticModel>() {

                        @Override
                        public SimpleSomaticModel apply(CSVRecord record) {
                            final Map<String, String> recordMap = record.toMap();
                            return new SimpleSomaticModel(recordMap);
                        }
                    }).toList();
            this.fileHandler.transformImportDataToTsvStagingFile(somaticList, MutationModel.getTransformationModel());
            logger.info("transformation complete ");
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public Path call() throws Exception {
        logger.info("Transformer invoked");
        this.processSimpleSomaticData();
        return this.icgcFilePath;
    }
    /*
     main method for standalone testing
     */
    public static void main(String... args) {
        ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));

        SimpleSomaticFileTransformer transformer = new SimpleSomaticFileTransformer(
                new MutationFileHandlerImpl(), Paths.get("/tmp/icgc/EOPC-DE"));
        String fn = "/tmp/EOPC-DE.tsv";
        transformer.setIcgcFilePath(Paths.get(fn));
        ListenableFuture<Path> p = service.submit(transformer);

            try {
                logger.info("Path " +p.get(90,TimeUnit.SECONDS));
                p.cancel(true);
                service.shutdown();
                logger.info("service shutdown ");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
            }
        logger.info("FINIS");



    }


}
