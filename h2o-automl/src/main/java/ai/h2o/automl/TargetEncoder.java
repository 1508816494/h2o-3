package ai.h2o.automl;

import water.DKV;
import water.Key;
import water.fvec.Frame;
import water.fvec.Vec;
import water.rapids.Rapids;
import water.rapids.Val;
import water.util.TwoDimTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static water.util.FrameUtils.getColumnIndexByName;

// TODO probably should call this logic from FrameUtils
public class TargetEncoder {

    public static class HoldoutType {
        public static final byte LeaveOneOut  =  0;
        public static final byte KFold  =  1;
        public static final byte None  =  2;
    }

    /**
     *
     * @param columnNamesToEncode names of columns to apply target encoding to
     * @param targetColumnName target column index
     * @param foldColumnName should contain index of column as String. TODO Change later into suitable type.
     */
    //TODO do we need to do this preparation before as a separate phase? because we are grouping twice.
    //TODO At least it seems that way in the case of KFold. But even if we need to preprocess for other types of TE calculations... we should not affect KFOLD case anyway.
    public Map<String, Frame> prepareEncodingMap(Frame data, String[] columnNamesToEncode, String targetColumnName, String foldColumnName) {

        //Validate input data. Not sure whether we should check some of these.

        if(data == null) throw new IllegalStateException("Argument 'data' is missing, with no default");

        if(columnNamesToEncode == null || columnNamesToEncode.length == 0)
            throw new IllegalStateException("Argument 'columnsToEncode' is not defined or empty");

        if(targetColumnName == null || targetColumnName.equals(""))
            throw new IllegalStateException("Argument 'target' is missing, with no default");

        if(! checkAllTEColumnsAreCategorical(data, columnNamesToEncode))
            throw new IllegalStateException("Argument 'columnsToEncode' should contain only names of categorical columns");

        if(Arrays.asList(columnNamesToEncode).contains(targetColumnName)) {
            throw new IllegalArgumentException("Columns for target encoding contain target column.");
        }

        int targetIndex = getColumnIndexByName(data, targetColumnName);

        Frame  dataWithoutNAsForTarget = filterOutNAsFromTargetColumn(data, targetIndex);

        Frame dataWithEncodedTarget = ensureTargetColumnIsNumericOrBinaryCategorical(dataWithoutNAsForTarget, targetIndex);

        Map<String, Frame> columnToEncodingMap = new HashMap<String, Frame>();

        for ( String teColumnName: columnNamesToEncode) { // TODO maybe we can do it in parallel
            Frame teColumnFrame = null;
            int colIndex = getColumnIndexByName(dataWithEncodedTarget, teColumnName);
            String tree = null;
            if (foldColumnName == null) {
                tree = String.format("(GB %s [%d] sum %s \"all\" nrow %s \"all\")", dataWithEncodedTarget._key, colIndex, targetIndex, targetIndex);
            } else {
                int foldColumnIndex = getColumnIndexByName(dataWithEncodedTarget, foldColumnName);

                tree = String.format("(GB %s [%d, %d] sum %s \"all\" nrow %s \"all\")", dataWithEncodedTarget._key, colIndex, foldColumnIndex, targetIndex, targetIndex);
            }
            Val val = Rapids.exec(tree);
            teColumnFrame = val.getFrame();
            teColumnFrame._key = Key.make(dataWithEncodedTarget._key.toString() + "_" + teColumnName + "_encodingMap");
            DKV.put(teColumnFrame._key, teColumnFrame);

            renameColumn(teColumnFrame, "sum_"+ targetColumnName, "numerator");
            renameColumn(teColumnFrame, "nrow", "denominator");

            columnToEncodingMap.put(teColumnName, teColumnFrame);
        }

        dataWithEncodedTarget.delete();
        dataWithoutNAsForTarget.delete();

        return columnToEncodingMap;
    }

    public Frame ensureTargetColumnIsNumericOrBinaryCategorical(Frame data, String targetColumnName) {
        return ensureTargetColumnIsNumericOrBinaryCategorical(data, getColumnIndexByName(data, targetColumnName));
    };

    public Frame ensureTargetColumnIsNumericOrBinaryCategorical(Frame data, int targetIndex) {
        if (data.vec(targetIndex).isCategorical()){
            Vec targetVec = data.vec(targetIndex);
            if(targetVec.cardinality() == 2) {
                return transformBinaryTargetColumn(data, targetIndex);
            }
            else {
                throw new IllegalStateException("`target` must be a binary vector");
            }
        }
        else {
            if(! data.vec(targetIndex).isNumeric()) {
                throw new IllegalStateException("`target` must be a numeric or binary vector");
            }
            return data;
        }
    };

    public Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), null);
    }

    public Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex, String foldColumnName) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), foldColumnName);
    }

    public Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex, int foldColumnIndex) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), foldColumnName);
    }

    public String[] getColumnNamesBy(Frame data, int[] columnIndexes) {
        String [] allColumnNames = data._names.clone();
        ArrayList<String> columnNames = new ArrayList<String>();

        for(int idx : columnIndexes) {
            columnNames.add(allColumnNames[idx]);
        }
        return columnNames.toArray(new String[columnIndexes.length]);
    }
    public String getColumnNameBy(Frame data, int columnIndex) {
        String [] allColumnNames = data._names.clone();
        return allColumnNames[columnIndex];
    }

    public Frame renameColumn(Frame fr, int indexOfColumnToRename, String newName) {
        String[] names = fr.names();
        names[indexOfColumnToRename] = newName;
        fr.setNames(names);
        return fr;
    }

    public Frame renameColumn(Frame fr, String oldName, String newName) {
        return renameColumn(fr, getColumnIndexByName(fr, oldName), newName);
    }

    private Frame execRapidsAndGetFrame(String astTree) {
        Val val = Rapids.exec(astTree);
        Frame res = val.getFrame();
        res._key = Key.make();
        DKV.put(res);
        return res;
    }

    public Frame filterOutNAsFromTargetColumn(Frame data, int targetIndex)  {
        String tree = String.format("(rows %s  (!! (is.na (cols %s [%s] ) ) ) )", data._key, data._key, targetIndex);
        return execRapidsAndGetFrame(tree);
    }

    public Frame transformBinaryTargetColumn(Frame data, int targetIndex)  {
        String[] domains = data.vec(targetIndex).domain().clone();
        Arrays.sort(domains);
        String tree = String.format("(:= %s (ifelse (is.na (cols %s [%d] ) ) NA (ifelse (== (cols %s [%d] ) '%s' ) 0.0 1.0 ) )  [%d] [] )",
                data._key, data._key, targetIndex,  data._key, targetIndex, domains[0], targetIndex);
        return execRapidsAndGetFrame(tree);
    }

    public Frame getOutOfFoldData(Frame encodingMap, String foldColumnName, long currentFoldValue)  {
        int foldColumnIndexInEncodingMap = getColumnIndexByName(encodingMap, foldColumnName);
        String astTree = String.format("(rows %s (!= (cols %s [%d] ) %d ) )", encodingMap._key, encodingMap._key, foldColumnIndexInEncodingMap, currentFoldValue);
        return execRapidsAndGetFrame(astTree);
    }

    public long[] getUniqueValuesOfTheFoldColumn(Frame data, int columnIndex) {
        String tree = String.format("(unique (cols %s [%d]))", data._key, columnIndex);
        Frame frame = Rapids.exec(tree).getFrame();
        Vec uniqueValues = frame.vec(0);
        int length = (int) uniqueValues.length(); // We assume that fold column should not has many different values and we will fit into node's memory
        long[] uniqueValuesArr = new long[length];
        for(int i = 0; i < uniqueValues.length(); i++) {
            uniqueValuesArr[i] = uniqueValues.at8(i);
        }
        uniqueValues.remove();
        frame.delete();
        return uniqueValuesArr;
    }

    private boolean checkAllTEColumnsAreCategorical(Frame data, String[] columnsToEncode)  {
        for( String columnName : columnsToEncode) {
            int columnIndex = getColumnIndexByName(data, columnName);
            if(! data.vec(columnIndex).isCategorical()) return false;
        }
        return true;
    }

    public Frame groupByTEColumnAndAggregate(Frame data, int teColumnIndex) {
        int numeratorColumnIndex = getColumnIndexByName(data, "numerator");
        int denominatorColumnIndex = getColumnIndexByName(data, "denominator");
        String astTree = String.format("(GB %s [%d] sum %d \"all\" sum %d \"all\")", data._key, teColumnIndex, numeratorColumnIndex, denominatorColumnIndex);
        return execRapidsAndGetFrame(astTree);
    }

    public Frame rBind(Frame a, Frame b) {
        if(a == null) {
            assert b != null;
            return b;
        } else {
            String tree = String.format("(rbind %s %s)", a._key, b._key);
            return execRapidsAndGetFrame(tree);
        }
    }

    public Frame mergeByTEColumnAndFold(Frame a, Frame holdoutEncodeMap, int teColumnIndexOriginal, int foldColumnIndexOriginal, int teColumnIndex ) {
        int foldColumnIndexInEncodingMap = getColumnIndexByName(holdoutEncodeMap, "foldValueForMerge");
        String astTree = String.format("(merge %s %s TRUE FALSE [%d, %d] [%d, %d] 'auto' )", a._key, holdoutEncodeMap._key, teColumnIndexOriginal, foldColumnIndexOriginal, teColumnIndex, foldColumnIndexInEncodingMap);
        return execRapidsAndGetFrame(astTree);
    }

    public Frame mergeByTEColumn(Frame a, Frame b, int teColumnIndexOriginal, int teColumnIndex) {
        String astTree = String.format("(merge %s %s TRUE FALSE [%d] [%d] 'auto' )", a._key, b._key, teColumnIndexOriginal, teColumnIndex);
        return execRapidsAndGetFrame(astTree);
    }

    public Frame appendColumn(Frame a, long columnValue, String appendedColumnName ) {
        String astTree = String.format("( append %s %d '%s' )", a._key , columnValue, appendedColumnName);
        return execRapidsAndGetFrame(astTree);
    }

    // Maybe it's better to calculate mean before any aggregations?
    public double calculateGlobalMean(Frame fr) {
        int numeratorIndex = getColumnIndexByName(fr,"numerator");
        int denominatorIndex = getColumnIndexByName(fr,"denominator");
        String tree = String.format("( / (sum (cols %s [%d] )) (sum (cols %s [%d] )) )", fr._key, numeratorIndex, fr._key, denominatorIndex);
        Val val = Rapids.exec(tree);
        return val.getNum();
    }

    public Frame calculateAndAppendBlendedTEEncoding(Frame fr, Frame encodingMap, String targetColumnName, String appendedColumnName ) {
        int numeratorIndex = getColumnIndexByName(fr,"numerator");
        int denominatorIndex = getColumnIndexByName(fr,"denominator");
        int targetColumnIndex = getColumnIndexByName(fr, targetColumnName);

        double globalMeanForTargetClass = calculateGlobalMean(encodingMap);
        double globalMeanForNonTargetClass = 1 - globalMeanForTargetClass;

        int k = 20;
        int f = 10;
        String expTerm = String.format("(exp ( / ( - %d (cols %s [%s] )) %d ))", k, fr._key, denominatorIndex, f);
        String lambdaTree = String.format("(  / 1     ( + 1 %s  )  ) ", expTerm);

        String oneMinusLambdaMultGlobalTerm = String.format(" ( * ( - 1 %s ) %f)", lambdaTree, globalMeanForTargetClass);

        String localTermTree = String.format("( ifelse ( == (cols %s [%d]) 0 ) ( ifelse ( == (cols %s [%d]) 1) %f  %f) ( / (cols %s [%d]) (cols %s [%d])) )",
                fr._key, denominatorIndex, fr._key, targetColumnIndex, globalMeanForTargetClass, globalMeanForNonTargetClass, fr._key,  numeratorIndex, fr._key, denominatorIndex);
        String lambdaMultLocalTerm = String.format("( * %s  %s  )", lambdaTree, localTermTree);
        String treeForLambda = String.format("( append %s ( + %s  %s )  '%s' )", fr._key, oneMinusLambdaMultGlobalTerm, lambdaMultLocalTerm, appendedColumnName);
        return Rapids.exec(treeForLambda).getFrame();
    }

    public Frame calculateAndAppendTEEncoding(Frame fr, Frame encodingMap, String targetColumnName, String appendedColumnName ) {
        // TODO int valueForSingleItemGroups = ??? ;
        // These groups have this singleness in common and we probably want to represent it somehow.
        // If we choose just global average then we just lose difference between single-row-groups that have different target values.
        // We can:  1) calculate averages per target value.   ( num. / denom. where target = [0,1] ).
        //              Group is so small that we even don't want to care about te_column's values.... just averages per target column's values.
        //         2) use  #single-item-groups_target0 / #of_targets_with_target0
        //         3) Count single-row-groups and calculate    #of_single_rows_with_target0 / #all_single_rows  ;  (and the same for target1)
        //TODO Introduce parameter for algorithm that will choose the way of calculating new value.
        int targetColumnIndex = getColumnIndexByName(fr, targetColumnName);
        int numeratorIndex = getColumnIndexByName(fr,"numerator");
        int denominatorIndex = getColumnIndexByName(fr,"denominator");
        double globalMeanForTargetClass = calculateGlobalMean(encodingMap);
        double globalMeanForNonTargetClass = 1 - globalMeanForTargetClass;
        String astTree = String.format("( append %s ( ifelse ( == (cols %s [%d]) 0 ) ( ifelse ( == (cols %s [%d]) 1) %f  %f) ( / (cols %s [%d]) (cols %s [%d])) ) '%s' )",
                fr._key , fr._key, denominatorIndex, fr._key, targetColumnIndex, globalMeanForTargetClass, globalMeanForNonTargetClass,  fr._key, numeratorIndex, fr._key, denominatorIndex, appendedColumnName);
        return execRapidsAndGetFrame(astTree);
    }

    public Frame addNoise(Frame fr, String applyToColumnName, double noiseLevel, double seed) {
        int appyToColumnIndex = getColumnIndexByName(fr, applyToColumnName);
        String tree = String.format("(:= %s (+ (cols %s [%d] ) (- (* (* (h2o.runif %s %f ) 2.0 ) %f ) %f ) ) [%d] [] )", fr._key, fr._key, appyToColumnIndex, fr._key, seed, noiseLevel, noiseLevel, appyToColumnIndex);
        return Rapids.exec(tree).getFrame();
    }

    public Frame subtractTargetValueForLOO(Frame data, String targetColumnName)  {
        int numeratorIndex = getColumnIndexByName(data,"numerator");
        int denominatorIndex = getColumnIndexByName(data,"denominator");
        int targetIndex = getColumnIndexByName(data, targetColumnName);

        String treeNumerator = String.format("(:= %s (ifelse (is.na (cols %s [%d] ) )   (cols %s [%d] )   (- (cols %s [%d] )  (cols %s [%d] )  ) )  [%d] [] )",
                data._key, data._key, targetIndex, data._key, numeratorIndex, data._key, numeratorIndex, data._key, targetIndex,  numeratorIndex);
        Frame withNumeratorSubtracted = execRapidsAndGetFrame(treeNumerator);

        Key<Frame> tmpNumeratorFrame = withNumeratorSubtracted._key;
        String treeDenominator = String.format("(:= %s (ifelse (is.na (cols %s [%d] ) )   (cols %s [%d] )   (- (cols %s [%d] )  1  ) )  [%d] [] )",
                tmpNumeratorFrame, tmpNumeratorFrame, targetIndex, tmpNumeratorFrame, denominatorIndex, tmpNumeratorFrame, denominatorIndex, denominatorIndex);
        Frame result = execRapidsAndGetFrame(treeDenominator);
        withNumeratorSubtracted.delete();
        return result;
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnsToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> columnToEncodingMap,
                                     byte holdoutType,
                                     String foldColumnName,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     double seed) {

        if(noiseLevel < 0 )
            throw new IllegalStateException("`noiseLevel` must be non-negative");

        //TODO Should we remove string columns from `data` as it is done in R version (see: https://0xdata.atlassian.net/browse/PUBDEV-5266) ?

        Frame dataCopy = data.deepCopy(Key.make().toString());
        DKV.put(dataCopy);

        Frame dataWithEncodedTarget = ensureTargetColumnIsNumericOrBinaryCategorical(dataCopy, targetColumnName); // TODO target value could be NA... it is fine. Check this.

        Frame dataWithAllEncodings = dataWithEncodedTarget.deepCopy(Key.make().toString());
        DKV.put(dataWithAllEncodings);


        for ( String teColumnName: columnsToEncode) {

            String newEncodedColumnName = teColumnName + "_te";

            Frame dataWithMergedAggregations = null;
            Frame dataWithEncodings = null;
            Frame dataWithEncodingsAndNoise = null;

            Frame targetEncodingMap = columnToEncodingMap.get(teColumnName);

            int teColumnIndex = getColumnIndexByName(dataWithAllEncodings, teColumnName);
            Frame holdoutEncodeMap = null;

            switch( holdoutType ) {
                case HoldoutType.KFold:
                    if(foldColumnName == null)
                        throw new IllegalStateException("`foldColumn` must be provided for holdoutType = KFold");

                    int teColumnIndexInEncodingMap = getColumnIndexByName(targetEncodingMap, teColumnName);

                    int foldColumnIndex = getColumnIndexByName(dataWithAllEncodings, foldColumnName);
                    long[] foldValues = getUniqueValuesOfTheFoldColumn(targetEncodingMap, 1);

                    for(long foldValue : foldValues) { // TODO what if our te column is not represented in every foldValue? Then when merging with original dataset we will get NA'a on the right side
                        Frame outOfFoldData = getOutOfFoldData(targetEncodingMap, foldColumnName, foldValue);


                        Frame groupedByTEColumnAndAggregate = groupByTEColumnAndAggregate(outOfFoldData, teColumnIndexInEncodingMap);

                        renameColumn(groupedByTEColumnAndAggregate, "sum_numerator", "numerator");
                        renameColumn(groupedByTEColumnAndAggregate, "sum_denominator", "denominator");

                        Frame groupedWithAppendedFoldColumn = appendColumn(groupedByTEColumnAndAggregate, foldValue, "foldValueForMerge");

                        if(holdoutEncodeMap == null) {
                            holdoutEncodeMap = groupedWithAppendedFoldColumn;
                        }
                        else {
                            Frame newHoldoutEncodeMap = rBind(holdoutEncodeMap, groupedWithAppendedFoldColumn);
                            groupedWithAppendedFoldColumn.delete();
                            holdoutEncodeMap.delete();
                            holdoutEncodeMap = newHoldoutEncodeMap;
                        }

                        outOfFoldData.delete();
                        groupedByTEColumnAndAggregate.delete();
                    }

                    dataWithMergedAggregations = mergeByTEColumnAndFold(dataWithAllEncodings, holdoutEncodeMap, teColumnIndex, foldColumnIndex, teColumnIndexInEncodingMap);

                    dataWithEncodings = calculateEncoding(dataWithMergedAggregations, targetEncodingMap, targetColumnName, newEncodedColumnName, withBlendedAvg);

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    Vec removedNumK = dataWithEncodingsAndNoise.remove("numerator");
                    removedNumK.remove();
                    Vec removedDenK = dataWithEncodingsAndNoise.remove("denominator");
                    removedDenK.remove();

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    dataWithEncodingsAndNoise.delete();
                    holdoutEncodeMap.delete();

                    break;
                case HoldoutType.LeaveOneOut:

                    Frame groupedTargetEncodingMap = groupingIgnoringFordColumn(foldColumnName, targetEncodingMap, teColumnName);

                    int teColumnIndexInGroupedEncodingMap = getColumnIndexByName(groupedTargetEncodingMap, teColumnName);
                    dataWithMergedAggregations = mergeByTEColumn(dataWithAllEncodings, groupedTargetEncodingMap, teColumnIndex, teColumnIndexInGroupedEncodingMap);

                    Frame preparedFrame = subtractTargetValueForLOO(dataWithMergedAggregations,  targetColumnName);

                    dataWithEncodings = calculateEncoding(preparedFrame, groupedTargetEncodingMap, targetColumnName, newEncodedColumnName, withBlendedAvg); // do we really need to pass groupedTargetEncodingMap again?

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    Vec removedNumLoo = dataWithEncodingsAndNoise.remove("numerator");
                    removedNumLoo.remove();
                    Vec removedDenLoo = dataWithEncodingsAndNoise.remove("denominator");
                    removedDenLoo.remove();

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    preparedFrame.delete();
                    dataWithEncodingsAndNoise.delete();
                    groupedTargetEncodingMap.delete();

                    break;
                case HoldoutType.None:
                    // TODO we'd better don't group it with folds during creation of targetEncodingMap
                    Frame groupedTargetEncodingMapForNone = groupingIgnoringFordColumn(foldColumnName, targetEncodingMap, teColumnName);

                    printOutFrameAsTable(groupedTargetEncodingMapForNone);
                    int teColumnIndexInGroupedEncodingMapNone = getColumnIndexByName(groupedTargetEncodingMapForNone, teColumnName);
                    dataWithMergedAggregations = mergeByTEColumn(dataWithAllEncodings, groupedTargetEncodingMapForNone, teColumnIndex, teColumnIndexInGroupedEncodingMapNone);

                    dataWithEncodings = calculateEncoding(dataWithMergedAggregations, groupedTargetEncodingMapForNone, targetColumnName, newEncodedColumnName, withBlendedAvg);

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    Vec removedNumeratorNone = dataWithEncodingsAndNoise.remove("numerator");
                    removedNumeratorNone.remove();
                    Vec removedDenominatorNone = dataWithEncodingsAndNoise.remove("denominator");
                    removedDenominatorNone.remove();

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    dataWithEncodingsAndNoise.delete();
                    groupedTargetEncodingMapForNone.delete();
            }

            dataWithMergedAggregations.delete();
            dataWithEncodings.delete();
        }

        dataCopy.delete();
        dataWithEncodedTarget.delete();

        return dataWithAllEncodings;
    }

    private Frame calculateEncoding(Frame preparedFrame, Frame encodingMap, String targetColumnName, String newEncodedColumnName, boolean withBlendedAvg) {
        if (withBlendedAvg) {
            return calculateAndAppendBlendedTEEncoding(preparedFrame, encodingMap, targetColumnName, newEncodedColumnName);

        } else {

            return calculateAndAppendTEEncoding(preparedFrame, encodingMap, targetColumnName, newEncodedColumnName);
        }
    }

    private Frame applyNoise(Frame frameWithEncodings, String newEncodedColumnName, double noiseLevel, double seed) {
        if(noiseLevel > 0) {
            return addNoise(frameWithEncodings, newEncodedColumnName, noiseLevel, seed);
        } else {
            return frameWithEncodings;
        }
    }

    private Frame groupingIgnoringFordColumn(String foldColumnName, Frame targetEncodingMap, String teColumnName) {
        if(foldColumnName != null) { // TODO we can't rely only on absence of the column name passed. User is able not to provide foldColumn name to apply method.
          System.out.println(" #### Grouping (back) targetEncodingMap without folds");
          int teColumnIndex = getColumnIndexByName(targetEncodingMap, teColumnName);

          Frame newTargetEncodingMap = groupByTEColumnAndAggregate(targetEncodingMap, teColumnIndex);
          renameColumn(newTargetEncodingMap, "sum_numerator", "numerator");
          renameColumn(newTargetEncodingMap, "sum_denominator", "denominator");
          return newTargetEncodingMap;
        } else {
            Frame targetEncodingMapCopy = targetEncodingMap.deepCopy(Key.make().toString());
            DKV.put(targetEncodingMapCopy);
            return targetEncodingMapCopy;
        }
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnsToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> targetEncodingMap,
                                     byte holdoutType,
                                     String foldColumn,
                                     boolean withBlendedAvg) {
        double defaultNoiseLevel = 0.01;
        double noiseLevel = 0.0;
        int targetIndex = getColumnIndexByName(data, targetColumnName);
        Vec targetVec = data.vec(targetIndex);
        if(targetVec.isNumeric()) {
            noiseLevel = defaultNoiseLevel * (targetVec.max() - targetVec.min());
        } else {
            noiseLevel = defaultNoiseLevel;
        }
        return this.applyTargetEncoding(data, columnsToEncode, targetColumnName, targetEncodingMap, holdoutType, foldColumn, withBlendedAvg, noiseLevel, 1234.0);
    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte holdoutType,
                                     int foldColumnIndex,
                                     boolean withBlendedAvg) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetIndex);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return this.applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, holdoutType, foldColumnName, withBlendedAvg);

    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte holdoutType,
                                     int foldColumnIndex,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     double seed) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetIndex);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return this.applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, holdoutType, foldColumnName, withBlendedAvg, noiseLevel, seed);

    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetColumnIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte holdoutType,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     double seed) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetColumnIndex);
        return applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, holdoutType, withBlendedAvg, noiseLevel, seed);
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnNamesToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> targetEncodingMap,
                                     byte holdoutType,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     double seed) {
        assert holdoutType != HoldoutType.KFold : "Use another overloaded method for KFold holdout type.";
        return applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, holdoutType, null, withBlendedAvg, noiseLevel, seed);
    }

    // TODO remove.
    private void printOutFrameAsTable(Frame fr) {

        TwoDimTable twoDimTable = fr.toTwoDimTable();
        System.out.println(twoDimTable.toString());
    }
    private void printOutFrameAsTable(Frame fr, boolean full, boolean rollups) {

        TwoDimTable twoDimTable = fr.toTwoDimTable(0, 10000, rollups);
        System.out.println(twoDimTable.toString(2, full));
    }
}
