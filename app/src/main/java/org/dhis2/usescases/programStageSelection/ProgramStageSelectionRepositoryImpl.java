package org.dhis2.usescases.programStageSelection;

import android.database.Cursor;

import com.squareup.sqlbrite2.BriteDatabase;

import org.dhis2.data.forms.RulesRepository;
import org.dhis2.data.tuples.Pair;
import org.dhis2.utils.DateUtils;
import org.dhis2.utils.EventCreationType;
import org.dhis2.utils.Result;
import org.dhis2.utils.SqlConstants;
import org.hisp.dhis.android.core.common.ObjectStyle;
import org.hisp.dhis.android.core.common.State;
import org.hisp.dhis.android.core.event.EventStatus;
import org.hisp.dhis.android.core.program.ProgramStage;
import org.hisp.dhis.android.core.program.ProgramStageModel;
import org.hisp.dhis.rules.RuleEngine;
import org.hisp.dhis.rules.RuleEngineContext;
import org.hisp.dhis.rules.RuleExpressionEvaluator;
import org.hisp.dhis.rules.models.RuleAttributeValue;
import org.hisp.dhis.rules.models.RuleDataValue;
import org.hisp.dhis.rules.models.RuleEffect;
import org.hisp.dhis.rules.models.RuleEnrollment;
import org.hisp.dhis.rules.models.RuleEvent;
import org.hisp.dhis.rules.models.TriggerEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import androidx.annotation.NonNull;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;

import static android.text.TextUtils.isEmpty;

/**
 * QUADRAM. Created by ppajuelo on 02/11/2017.
 */

public class ProgramStageSelectionRepositoryImpl implements ProgramStageSelectionRepository {

    private static final String PROGRAM_STAGE_QUERY = String.format("SELECT * FROM %s WHERE %s.%s = ? ORDER BY %s.%s ASC",
            SqlConstants.PROGRAM_STAGE_TABLE, SqlConstants.PROGRAM_STAGE_TABLE, ProgramStageModel.Columns.PROGRAM,
            SqlConstants.PROGRAM_STAGE_TABLE, ProgramStageModel.Columns.SORT_ORDER);

    private static final String PROGRAM_STAGE_QUERY_SCHEDULE = String.format("SELECT * FROM %s WHERE %s.%s = ? AND %s.%s = '0' ORDER BY %s.%s ASC",
            SqlConstants.PROGRAM_STAGE_TABLE, SqlConstants.PROGRAM_STAGE_TABLE, ProgramStageModel.Columns.PROGRAM,
            SqlConstants.PROGRAM_STAGE_TABLE, ProgramStageModel.Columns.HIDE_DUE_DATE,
            SqlConstants.PROGRAM_STAGE_TABLE, ProgramStageModel.Columns.SORT_ORDER);

    private static final String CURRENT_PROGRAM_STAGES = "SELECT ProgramStage.* FROM ProgramStage WHERE ProgramStage.uid IN " +
            "(SELECT DISTINCT Event.programStage FROM Event WHERE Event.enrollment = ? AND Event.State != 'TO_DELETE' ) ORDER BY ProgramStage.sortOrder ASC";

    private static final String QUERY_ENROLLMENT = "SELECT\n" +
            "  Enrollment.uid,\n" +
            "  Enrollment.incidentDate,\n" +
            "  Enrollment.enrollmentDate,\n" +
            "  Enrollment.status,\n" +
            "  Enrollment.organisationUnit,\n" +
            "  Program.displayName\n" +
            "FROM Enrollment\n" +
            "JOIN Program ON Program.uid = Enrollment.program\n" +
            "WHERE Enrollment.uid = ? \n" +
            "LIMIT 1;";

    private static final String QUERY_ATTRIBUTE_VALUES = "SELECT\n" +
            "  Field.id,\n" +
            "  Value.value,\n" +
            "  ProgramRuleVariable.useCodeForOptionSet,\n" +
            "  Option.code,\n" +
            "  Option.name\n" +
            "FROM (Enrollment INNER JOIN Program ON Program.uid = Enrollment.program)\n" +
            "  INNER JOIN (\n" +
            "      SELECT\n" +
            "        TrackedEntityAttribute.uid AS id,\n" +
            "        TrackedEntityAttribute.optionSet AS optionSet,\n" +
            "        ProgramTrackedEntityAttribute.program AS program\n" +
            "      FROM ProgramTrackedEntityAttribute INNER JOIN TrackedEntityAttribute\n" +
            "          ON TrackedEntityAttribute.uid = ProgramTrackedEntityAttribute.trackedEntityAttribute\n" +
            "    ) AS Field ON Field.program = Program.uid\n" +
            "  INNER JOIN TrackedEntityAttributeValue AS Value ON (\n" +
            "    Value.trackedEntityAttribute = Field.id\n" +
            "        AND Value.trackedEntityInstance = Enrollment.trackedEntityInstance)\n" +
            "  LEFT JOIN ProgramRuleVariable ON ProgramRuleVariable.trackedEntityAttribute = Field.id " +
            "  LEFT JOIN Option ON (Option.optionSet = Field.optionSet AND Option.code = Value.value) " +
            "WHERE Enrollment.uid = ? AND Value.value IS NOT NULL;";

    private static final String QUERY_EVENT = "SELECT Event.uid,\n" +
            "  Event.programStage,\n" +
            "  Event.status,\n" +
            "  Event.eventDate,\n" +
            "  Event.dueDate,\n" +
            "  Event.organisationUnit,\n" +
            "  ProgramStage.displayName\n" +
            "FROM Event\n" +
            "JOIN ProgramStage ON ProgramStage.uid = Event.programStage\n" +
            "WHERE Event.enrollment = ?\n" +
            " AND " + SqlConstants.EVENT_TABLE + "." + SqlConstants.EVENT_STATE + " != '" + State.TO_DELETE + "'";

    private static final String QUERY_VALUES = "SELECT " +
            "  Event.eventDate," +
            "  Event.programStage," +
            "  TrackedEntityDataValue.dataElement," +
            "  TrackedEntityDataValue.value," +
            "  ProgramRuleVariable.useCodeForOptionSet," +
            "  Option.code," +
            "  Option.name" +
            " FROM TrackedEntityDataValue " +
            "  INNER JOIN Event ON TrackedEntityDataValue.event = Event.uid " +
            "  INNER JOIN DataElement ON DataElement.uid = TrackedEntityDataValue.dataElement " +
            "  LEFT JOIN ProgramRuleVariable ON ProgramRuleVariable.dataElement = DataElement.uid " +
            "  LEFT JOIN Option ON (Option.optionSet = DataElement.optionSet AND Option.code = TrackedEntityDataValue.value) " +
            " WHERE Event.uid = ? AND value IS NOT NULL AND " + SqlConstants.EVENT_TABLE + "." + SqlConstants.EVENT_STATE + " != '" + State.TO_DELETE + "';";

    private final BriteDatabase briteDatabase;
    private final Flowable<RuleEngine> cachedRuleEngineFlowable;
    private final String enrollmentUid;
    private final String eventCreationType;

    ProgramStageSelectionRepositoryImpl(BriteDatabase briteDatabase, RuleExpressionEvaluator evaluator, RulesRepository rulesRepository, String programUid, String enrollmentUid, String eventCreationType) {
        this.briteDatabase = briteDatabase;
        this.enrollmentUid = enrollmentUid;
        this.eventCreationType = eventCreationType;
        this.cachedRuleEngineFlowable =
                Flowable.zip(
                        rulesRepository.rulesNew(programUid),
                        rulesRepository.ruleVariablesProgramStages(programUid),
                        ruleEvents(enrollmentUid),
                        (rules, variables, ruleEvents) -> {
                            RuleEngine.Builder builder = RuleEngineContext.builder(evaluator)
                                    .rules(rules)
                                    .ruleVariables(variables)
                                    .calculatedValueMap(new HashMap<>())
                                    .supplementaryData(new HashMap<>())
                                    .build().toEngineBuilder();
                            return builder.events(ruleEvents)
                                    .triggerEnvironment(TriggerEnvironment.ANDROIDCLIENT)
                                    .build();
                        })
                        .cacheWithInitialCapacity(1);
    }

    private Flowable<List<RuleEvent>> ruleEvents(String enrollmentUid) {
        return briteDatabase.createQuery(SqlConstants.EVENT_TABLE, QUERY_EVENT, enrollmentUid == null ? "" : enrollmentUid)
                .mapToList(cursor -> {
                    List<RuleDataValue> dataValues = new ArrayList<>();
                    String eventUid = cursor.getString(0);
                    String programStageUid = cursor.getString(1);
                    Date eventDate = DateUtils.databaseDateFormat().parse(cursor.getString(3));
                    Date dueDate = cursor.isNull(4) ? eventDate : DateUtils.databaseDateFormat().parse(cursor.getString(4));
                    String orgUnit = cursor.getString(5);
                    String orgUnitCode = getOrgUnitCode(orgUnit);
                    String programStageName = cursor.getString(6);
                    String eventStatus;
                    if (cursor.getString(2).equals(EventStatus.VISITED.name()))
                        eventStatus = EventStatus.ACTIVE.name();
                    else
                        eventStatus = cursor.getString(2);

                    RuleEvent.Status status = RuleEvent.Status.valueOf(eventStatus);

                    try (Cursor dataValueCursor = briteDatabase.query(QUERY_VALUES, eventUid == null ? "" : eventUid)) {
                        if (dataValueCursor != null && dataValueCursor.moveToFirst()) {
                            for (int i = 0; i < dataValueCursor.getCount(); i++) {
                                Date eventDateV = DateUtils.databaseDateFormat().parse(cursor.getString(0));
                                String programStage = cursor.getString(1);
                                String dataElement = cursor.getString(2);
                                String value = cursor.getString(3) != null ? cursor.getString(3) : "";
                                boolean useCode = cursor.getInt(4) == 1;
                                String optionCode = cursor.getString(5);
                                String optionName = cursor.getString(6);
                                if (!isEmpty(optionCode) && !isEmpty(optionName))
                                    value = useCode ? optionCode : optionName; //If de has optionSet then check if value should be code or name for program rules
                                dataValues.add(RuleDataValue.create(eventDateV, programStage,
                                        dataElement, value));
                                dataValueCursor.moveToNext();
                            }
                        }
                    }

                    return RuleEvent.builder()
                            .event(eventUid)
                            .programStage(programStageUid)
                            .programStageName(programStageName)
                            .status(status)
                            .eventDate(eventDate)
                            .dueDate(dueDate)
                            .organisationUnit(orgUnit)
                            .organisationUnitCode(orgUnitCode)
                            .dataValues(dataValues)
                            .build();

                }).toFlowable(BackpressureStrategy.LATEST);
    }

    @NonNull
    private Flowable<List<RuleDataValue>> queryDataValues(String eventUid) {
        return briteDatabase.createQuery(Arrays.asList(SqlConstants.EVENT_TABLE,
                SqlConstants.TEI_DATA_VALUE_TABLE), QUERY_VALUES, eventUid == null ? "" : eventUid)
                .mapToList(cursor -> {
                    Date eventDate = DateUtils.databaseDateFormat().parse(cursor.getString(0));
                    String programStage = cursor.getString(1);
                    String dataElement = cursor.getString(2);
                    String value = cursor.getString(3) != null ? cursor.getString(3) : "";
                    boolean useCode = cursor.getInt(4) == 1;
                    String optionCode = cursor.getString(5);
                    String optionName = cursor.getString(6);
                    if (!isEmpty(optionCode) && !isEmpty(optionName))
                        value = useCode ? optionCode : optionName; //If de has optionSet then check if value should be code or name for program rules
                    return RuleDataValue.create(eventDate, programStage, dataElement, value);
                }).toFlowable(BackpressureStrategy.LATEST);
    }

    private Flowable<RuleEnrollment> ruleEnrollemt(String enrollmentUid) {
        return briteDatabase.createQuery(Arrays.asList(SqlConstants.ENROLLMENT_TABLE,
                SqlConstants.TE_ATTR_VALUE_TABLE), QUERY_ATTRIBUTE_VALUES, enrollmentUid == null ? "" : enrollmentUid)
                .mapToList(cursor -> RuleAttributeValue.create(
                        cursor.getString(0), cursor.getString(1))
                ).toFlowable(BackpressureStrategy.LATEST)
                .flatMap(attributeValues ->

                        briteDatabase.createQuery(SqlConstants.ENROLLMENT_TABLE, QUERY_ENROLLMENT, enrollmentUid == null ? "" : enrollmentUid)
                                .mapToOne(cursor -> {
                                    Date enrollmentDate = DateUtils.databaseDateFormat().parse(cursor.getString(2));
                                    Date incidentDate = cursor.isNull(1) ?
                                            enrollmentDate : DateUtils.databaseDateFormat().parse(cursor.getString(1));
                                    RuleEnrollment.Status status = RuleEnrollment.Status
                                            .valueOf(cursor.getString(3));
                                    String orgUnit = cursor.getString(4);
                                    String programName = cursor.getString(5);
                                    String ouCode = getOrgUnitCode(orgUnit);
                                    return RuleEnrollment.create(cursor.getString(0),
                                            incidentDate, enrollmentDate, status, orgUnit, ouCode, attributeValues, programName);
                                }).toFlowable(BackpressureStrategy.LATEST)
                );
    }

    @Nonnull
    private String getOrgUnitCode(String orgUnitUid) {
        String ouCode = "";
        try (Cursor cursor = briteDatabase.query("SELECT code FROM OrganisationUnit WHERE uid = ? LIMIT 1", orgUnitUid)) {
            if (cursor != null && cursor.moveToFirst()) {
                ouCode = cursor.getString(0);
            }
        }
        return ouCode;
    }

    @NonNull
    @Override
    public Observable<List<ProgramStage>> getProgramStages(String programUid) {
        return briteDatabase.createQuery(SqlConstants.PROGRAM_STAGE_TABLE, PROGRAM_STAGE_QUERY, programUid == null ? "" : programUid)
                .mapToList(ProgramStage::create);
    }

    @NonNull
    @Override
    public Flowable<List<ProgramStage>> enrollmentProgramStages(String programId, String enrollmentUid) {
        List<ProgramStage> enrollmentStages = new ArrayList<>();
        List<ProgramStage> selectableStages = new ArrayList<>();
        return briteDatabase.createQuery(SqlConstants.PROGRAM_STAGE_TABLE, CURRENT_PROGRAM_STAGES, enrollmentUid == null ? "" : enrollmentUid)
                .mapToList(ProgramStage::create)
                .flatMap(data -> {
                    enrollmentStages.addAll(data);
                    return briteDatabase.createQuery(SqlConstants.PROGRAM_STAGE_TABLE,
                            !Objects.equals(eventCreationType, EventCreationType.SCHEDULE.name()) ? PROGRAM_STAGE_QUERY : PROGRAM_STAGE_QUERY_SCHEDULE, programId == null ? "" : programId)
                            .mapToList(ProgramStage::create);
                })
                .map(data -> {
                    boolean isSelectable;
                    for (ProgramStage programStage : data) {
                        isSelectable = true;
                        for (ProgramStage enrollmentStage : enrollmentStages) {
                            if (enrollmentStage.uid().equals(programStage.uid())) {
                                isSelectable = programStage.repeatable();
                            }
                        }

                        if (isSelectable)
                            selectableStages.add(programStage);
                    }
                    return selectableStages;
                }).toFlowable(BackpressureStrategy.LATEST);
    }

    @Override
    public Flowable<Result<RuleEffect>> calculate() {

        return ruleEnrollemt(enrollmentUid)
                .flatMap(enrollment ->
                        cachedRuleEngineFlowable
                                .switchMap(ruleEngine -> Flowable.fromCallable(ruleEngine.evaluate(enrollment))
                                        .map(Result::success)
                                        .onErrorReturn(error -> Result.failure(new Exception(error)))
                                )
                );
    }

    @Override
    public List<Pair<ProgramStage, ObjectStyle>> objectStyle(List<ProgramStage> programStageModels) {
        List<Pair<ProgramStage, ObjectStyle>> finalList = new ArrayList<>();
        for (ProgramStage stageModel : programStageModels) {
            ObjectStyle objectStyleModel = null;
            try (Cursor cursor = briteDatabase.query("SELECT * FROM ObjectStyle WHERE uid = ? LIMIT 1", stageModel.uid())) {
                if (cursor != null && cursor.moveToFirst()) {
                    objectStyleModel = ObjectStyle.create(cursor);
                }
            }
            if (objectStyleModel == null)
                objectStyleModel = ObjectStyle.builder().build();
            finalList.add(Pair.create(stageModel, objectStyleModel));
        }

        return finalList;
    }
}
