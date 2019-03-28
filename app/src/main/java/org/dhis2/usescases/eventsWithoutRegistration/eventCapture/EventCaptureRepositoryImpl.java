package org.dhis2.usescases.eventsWithoutRegistration.eventCapture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.squareup.sqlbrite2.BriteDatabase;

import org.dhis2.R;
import org.dhis2.data.forms.FormRepository;
import org.dhis2.data.forms.FormSectionViewModel;
import org.dhis2.data.forms.RulesRepository;
import org.dhis2.data.forms.dataentry.fields.FieldViewModel;
import org.dhis2.data.forms.dataentry.fields.FieldViewModelFactory;
import org.dhis2.data.forms.dataentry.fields.FieldViewModelFactoryImpl;
import org.dhis2.utils.DateUtils;
import org.dhis2.utils.Result;
import org.dhis2.utils.SqlConstants;
import org.hisp.dhis.android.core.D2;
import org.hisp.dhis.android.core.common.BaseIdentifiableObject;
import org.hisp.dhis.android.core.common.ObjectStyle;
import org.hisp.dhis.android.core.common.State;
import org.hisp.dhis.android.core.common.ValueType;
import org.hisp.dhis.android.core.common.ValueTypeDeviceRendering;
import org.hisp.dhis.android.core.enrollment.Enrollment;
import org.hisp.dhis.android.core.enrollment.EnrollmentStatus;
import org.hisp.dhis.android.core.event.Event;
import org.hisp.dhis.android.core.event.EventStatus;
import org.hisp.dhis.android.core.organisationunit.OrganisationUnit;
import org.hisp.dhis.android.core.program.ProgramRule;
import org.hisp.dhis.android.core.program.ProgramRuleAction;
import org.hisp.dhis.android.core.program.ProgramRuleActionType;
import org.hisp.dhis.android.core.program.ProgramRuleVariable;
import org.hisp.dhis.android.core.program.ProgramStage;
import org.hisp.dhis.android.core.program.ProgramStageSectionDeviceRendering;
import org.hisp.dhis.android.core.program.ProgramStageSectionRenderingType;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityInstance;
import org.hisp.dhis.rules.models.Rule;
import org.hisp.dhis.rules.models.RuleAction;
import org.hisp.dhis.rules.models.RuleDataValue;
import org.hisp.dhis.rules.models.RuleEffect;
import org.hisp.dhis.rules.models.RuleEvent;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;

import static android.text.TextUtils.isEmpty;
import static org.dhis2.utils.SqlConstants.FROM;
import static org.dhis2.utils.SqlConstants.NOT_EQUALS;
import static org.dhis2.utils.SqlConstants.QUOTE;
import static org.dhis2.utils.SqlConstants.UID_EQUALS_QUESTION_MARK;

/**
 * QUADRAM. Created by ppajuelo on 19/11/2018.
 */
public class EventCaptureRepositoryImpl implements EventCaptureContract.EventCaptureRepository {

    private final FieldViewModelFactory fieldFactory;


    private static final List<String> SECTION_TABLES = Arrays.asList(
            SqlConstants.EVENT_TABLE, SqlConstants.PROGRAM_TABLE, SqlConstants.PROGRAM_STAGE_TABLE, SqlConstants.PROGRAM_STAGE_SECTION_TABLE);
    private static final String SELECT_SECTIONS = "SELECT\n" +
            "  Program.uid AS programUid,\n" +
            "  ProgramStage.uid AS programStageUid,\n" +
            "  ProgramStageSection.uid AS programStageSectionUid,\n" +
            "  ProgramStageSection.displayName AS programStageSectionDisplayName,\n" +
            "  ProgramStage.displayName AS programStageDisplayName,\n" +
            "  ProgramStageSection.mobileRenderType AS renderType\n" +
            FROM + SqlConstants.EVENT_TABLE + "\n" +
            "  JOIN Program ON Event.program = Program.uid\n" +
            "  JOIN ProgramStage ON Event.programStage = ProgramStage.uid\n" +
            "  LEFT OUTER JOIN ProgramStageSection ON ProgramStageSection.programStage = Event.programStage\n" +
            "WHERE Event.uid = ?\n" +
            "AND " + SqlConstants.EVENT_TABLE + "." + SqlConstants.EVENT_STATE + NOT_EQUALS + QUOTE + State.TO_DELETE + "' ORDER BY ProgramStageSection.sortOrder";

    private static final String QUERY = "SELECT\n" +
            "  Field.id,\n" +
            "  Field.label,\n" +
            "  Field.type,\n" +
            "  Field.mandatory,\n" +
            "  Field.optionSet,\n" +
            "  Value.value,\n" +
            "  Option.displayName,\n" +
            "  Field.section,\n" +
            "  Field.allowFutureDate,\n" +
            "  Event.status,\n" +
            "  Field.formLabel,\n" +
            "  Field.displayDescription,\n" +
            "  Field.formOrder,\n" +
            "  Field.sectionOrder\n" +
            FROM + SqlConstants.EVENT_TABLE + "\n" +
            "  LEFT OUTER JOIN (\n" +
            "      SELECT\n" +
            "        DataElement.displayName AS label,\n" +
            "        DataElement.valueType AS type,\n" +
            "        DataElement.uid AS id,\n" +
            "        DataElement.optionSet AS optionSet,\n" +
            "        DataElement.displayFormName AS formLabel,\n" +
            "        ProgramStageDataElement.sortOrder AS formOrder,\n" +
            "        ProgramStageDataElement.programStage AS stage,\n" +
            "        ProgramStageDataElement.compulsory AS mandatory,\n" +
            "        ProgramStageSectionDataElementLink.programStageSection AS section,\n" +
            "        ProgramStageDataElement.allowFutureDate AS allowFutureDate,\n" +
            "        DataElement.displayDescription AS displayDescription,\n" +
            "        ProgramStageSectionDataElementLink.sortOrder AS sectionOrder\n" + //This should override dataElement formOrder
            "      FROM ProgramStageDataElement\n" +
            "        INNER JOIN DataElement ON DataElement.uid = ProgramStageDataElement.dataElement\n" +
            "        LEFT JOIN ProgramStageSection ON ProgramStageSection.programStage = ProgramStageDataElement.programStage\n" +
            "        LEFT JOIN ProgramStageSectionDataElementLink ON ProgramStageSectionDataElementLink.programStageSection = ProgramStageSection.uid AND ProgramStageSectionDataElementLink.dataElement = DataElement.uid\n" +
            "    ) AS Field ON (Field.stage = Event.programStage)\n" +
            "  LEFT OUTER JOIN TrackedEntityDataValue AS Value ON (\n" +
            "    Value.event = Event.uid AND Value.dataElement = Field.id\n" +
            "  )\n" +
            "  LEFT OUTER JOIN Option ON (\n" +
            "    Field.optionSet = Option.optionSet AND Value.value = Option.code\n" +
            "  )\n" +
            " %s  " +
            "ORDER BY CASE" +
            " WHEN Field.sectionOrder IS NULL THEN Field.formOrder" +
            " WHEN Field.sectionOrder IS NOT NULL THEN Field.sectionOrder" +
            " END ASC;";

    private static final String OPTIONS = "SELECT Option.uid, Option.displayName, Option.code FROM Option WHERE Option.optionSet = ?";

    private final BriteDatabase briteDatabase;
    private final String eventUid;
    private final FormRepository formRepository;
    private final D2 d2;
    private boolean accessDataWrite;
    private String lastUpdatedUid;
    private RuleEvent.Builder eventBuilder;
    private Map<String, List<Rule>> dataElementRules = new HashMap<>();

    public EventCaptureRepositoryImpl(Context context, BriteDatabase briteDatabase, FormRepository formRepository, String eventUid, D2 d2) {
        this.briteDatabase = briteDatabase;
        this.eventUid = eventUid;
        this.formRepository = formRepository;
        this.d2 = d2;

        Event event = d2.eventModule().events.uid(eventUid).withAllChildren().get();
        ProgramStage programStage = d2.programModule().programStages.uid(event.programStage()).withAllChildren().get();
        OrganisationUnit ou = d2.organisationUnitModule().organisationUnits.uid(event.organisationUnit()).withAllChildren().get();

        eventBuilder = RuleEvent.builder()
                .event(event.uid())
                .programStage(event.programStage())
                .programStageName(programStage.displayName())
                .status(RuleEvent.Status.valueOf(event.status().name()))
                .eventDate(event.eventDate())
                .dueDate(event.dueDate() != null ? event.dueDate() : event.eventDate())
                .organisationUnit(event.organisationUnit())
                .organisationUnitCode(ou.code());

        fieldFactory = new FieldViewModelFactoryImpl(
                context.getString(R.string.enter_text),
                context.getString(R.string.enter_long_text),
                context.getString(R.string.enter_number),
                context.getString(R.string.enter_integer),
                context.getString(R.string.enter_positive_integer),
                context.getString(R.string.enter_negative_integer),
                context.getString(R.string.enter_positive_integer_or_zero),
                context.getString(R.string.filter_options),
                context.getString(R.string.choose_date));

        loadDataElementRules(event);
    }

    private List<ProgramRule> getMandatoryRules(ProgramRule rule) {
        List<ProgramRule> mandatoryRules = new ArrayList<>();
        for (ProgramRuleAction action : rule.programRuleActions()) {
            if ((action.programRuleActionType() == ProgramRuleActionType.HIDEFIELD ||
                    action.programRuleActionType() == ProgramRuleActionType.HIDESECTION ||
                    action.programRuleActionType() == ProgramRuleActionType.ASSIGN ||
                    action.programRuleActionType() == ProgramRuleActionType.SHOWWARNING ||
                    action.programRuleActionType() == ProgramRuleActionType.SHOWERROR ||
                    action.programRuleActionType() == ProgramRuleActionType.HIDEOPTIONGROUP ||
                    action.programRuleActionType() == ProgramRuleActionType.HIDEOPTION)
                    && (!mandatoryRules.contains(rule))) {
                mandatoryRules.add(rule);
            }
        }
        return mandatoryRules;
    }

    private void loadDataElementRules(Event event) {
        List<ProgramRule> rules = d2.programModule().programRules.byProgramUid().eq(event.program()).withAllChildren().get();
        List<ProgramRule> mandatoryRules = new ArrayList<>();
        Iterator<ProgramRule> ruleIterator = rules.iterator();
        while (ruleIterator.hasNext()) {
            ProgramRule rule = ruleIterator.next();
            if ((rule.programStage() != null && rule.programStage().uid().equals(event.programStage())) || (rule.condition() == null)) {
                ruleIterator.remove();
            }
            mandatoryRules.addAll(getMandatoryRules(rule));
        }

        List<ProgramRuleVariable> variables = d2.programModule().programRuleVariables
                .byProgramUid().eq(event.program())
                .withAllChildren().get();
        Iterator<ProgramRuleVariable> variableIterator = variables.iterator();
        while (variableIterator.hasNext()) {
            ProgramRuleVariable variable = variableIterator.next();
            if ((variable.programStage() != null && variable.programStage().uid().equals(event.programStage())) || (variable.dataElement() == null)) {
                variableIterator.remove();
            }
        }

        setDataElementRules(variables, rules, mandatoryRules);
    }

    private void setDataElementRules(List<ProgramRuleVariable> variables, List<ProgramRule> rules, List<ProgramRule> mandatoryRules) {
        for (ProgramRuleVariable variable : variables) {
            if (variable.dataElement() != null && !dataElementRules.containsKey(variable.dataElement().uid()))
                dataElementRules.put(variable.dataElement().uid(), trasformToRule(mandatoryRules));
            for (ProgramRule rule : rules) {
                if (rule.condition().contains(variable.displayName()) || actionsContainsDE(rule.programRuleActions(), variable.displayName())) {
                    if (dataElementRules.get(variable.dataElement().uid()) == null)
                        dataElementRules.put(variable.dataElement().uid(), trasformToRule(mandatoryRules));
                    dataElementRules.get(variable.dataElement().uid()).add(trasformToRule(rule));
                }
            }
        }
    }

    private Rule trasformToRule(ProgramRule rule) {
        return Rule.create(
                rule.programStage() != null ? rule.programStage().uid() : null,
                rule.priority(),
                rule.condition(),
                transformToRuleAction(rule.programRuleActions()),
                rule.displayName());
    }

    private List<Rule> trasformToRule(List<ProgramRule> rules) {
        List<Rule> finalRules = new ArrayList<>();
        for (ProgramRule rule : rules) {
            finalRules.add(Rule.create(
                    rule.programStage() != null ? rule.programStage().uid() : null,
                    rule.priority(),
                    rule.condition(),
                    transformToRuleAction(rule.programRuleActions()),
                    rule.displayName()));
        }
        return finalRules;
    }

    private List<RuleAction> transformToRuleAction(List<ProgramRuleAction> programRuleActions) {
        List<RuleAction> ruleActions = new ArrayList<>();
        if (programRuleActions != null)
            for (ProgramRuleAction programRuleAction : programRuleActions)
                ruleActions.add(createRuleAction(programRuleAction));
        return ruleActions;
    }

    private RuleAction createRuleAction(ProgramRuleAction programRuleAction) {
        return RulesRepository.create(
                programRuleAction.programRuleActionType(),
                programRuleAction.programStage() != null ? programRuleAction.programStage().uid() : null,
                programRuleAction.programStageSection() != null ? programRuleAction.programStageSection().uid() : null,
                programRuleAction.trackedEntityAttribute() != null ? programRuleAction.trackedEntityAttribute().uid() : null,
                programRuleAction.dataElement() != null ? programRuleAction.dataElement().uid() : null,
                programRuleAction.location(),
                programRuleAction.content(),
                programRuleAction.data(),
                programRuleAction.option() != null ? programRuleAction.option().uid() : null,
                programRuleAction.optionGroup() != null ? programRuleAction.optionGroup().uid() : null);
    }

    private boolean actionsContainsDE(List<ProgramRuleAction> programRuleActions, String variableName) {
        boolean actionContainsDe = false;
        for (ProgramRuleAction ruleAction : programRuleActions) {
            if (ruleAction.data() != null && ruleAction.data().contains(variableName))
                actionContainsDe = true;

        }
        return actionContainsDe;
    }


    @Override
    public boolean isEnrollmentOpen() {
        Enrollment enrollment = d2.enrollmentModule().enrollments.uid(d2.eventModule().events.uid(eventUid).get().enrollment()).get();
        return enrollment == null || enrollment.status() == EnrollmentStatus.ACTIVE;
    }

    @Override
    public boolean isEnrollmentCancelled() {
        Enrollment enrollment = d2.enrollmentModule().enrollments.uid(d2.eventModule().events.uid(eventUid).get().enrollment()).get();
        if (enrollment == null)
            return false;
        else
            return d2.enrollmentModule().enrollments.uid(d2.eventModule().events.uid(eventUid).get().enrollment()).get().status() == EnrollmentStatus.CANCELLED;
    }

    @Override
    public Flowable<String> programStageName() {
        return Flowable.just(d2.eventModule().events.uid(eventUid).get())
                .map(event -> d2.programModule().programStages.uid(event.programStage()).get().displayName());
    }

    @Override
    public Flowable<String> eventDate() {
        return Flowable.just(d2.eventModule().events.uid(eventUid).get())
                .map(event -> DateUtils.uiDateFormat().format(event.eventDate()));
    }

    @Override
    public Flowable<String> orgUnit() {
        return Flowable.just(d2.eventModule().events.uid(eventUid).get())
                .map(event -> d2.organisationUnitModule().organisationUnits.uid(event.organisationUnit()).get().displayName());
    }


    @Override
    public Flowable<String> catOption() {
        return Flowable.just(d2.eventModule().events.uid(eventUid).get())
                .map(event -> d2.categoryModule().categoryOptionCombos.uid(event.attributeOptionCombo()))
                .map(categoryOptionComboRepo -> {
                    if (categoryOptionComboRepo.get() == null)
                        return "";
                    else
                        return categoryOptionComboRepo.get().displayName();
                });
    }

    @Override
    public Flowable<List<FormSectionViewModel>> eventSections() {
        return briteDatabase
                .createQuery(SECTION_TABLES, SELECT_SECTIONS, eventUid)
                .mapToList(this::mapToFormSectionViewModels)
                .distinctUntilChanged().toFlowable(BackpressureStrategy.LATEST);
    }

    @NonNull
    @Override
    public Flowable<List<FieldViewModel>> list(String sectionUid) {
        accessDataWrite = getAccessDataWrite();
        return briteDatabase
                .createQuery(SqlConstants.TEI_DATA_VALUE_TABLE, prepareStatement(sectionUid, eventUid))
                .mapToList(this::transform)
                .map(this::checkRenderType)
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private ProgramStageSectionRenderingType renderingType(String sectionUid) {
        ProgramStageSectionRenderingType renderingType = ProgramStageSectionRenderingType.LISTING;
        if (sectionUid != null) {
            ProgramStageSectionDeviceRendering stageSectionRendering = d2.programModule().programStageSections.uid(sectionUid).get().renderType().mobile();
            if (stageSectionRendering != null)
                renderingType = stageSectionRendering.type();
        }

        return renderingType;
    }

    private ValueTypeDeviceRendering getFieldRendering(String uid) {
        ValueTypeDeviceRendering fieldRendering = null;
        try (Cursor rendering = briteDatabase.query("SELECT ValueTypeDeviceRendering.* FROM ValueTypeDeviceRendering" +
                " JOIN ProgramStageDataElement ON ProgramStageDataElement.uid = ValueTypeDeviceRendering.uid" +
                " WHERE ProgramStageDataElement.uid = ?", uid)) {
            if (rendering != null && rendering.moveToFirst())
                fieldRendering = ValueTypeDeviceRendering.create(rendering);
        }
        return fieldRendering;
    }

    private ObjectStyle getObjectStyle(String uid) {
        ObjectStyle objectStyle = ObjectStyle.builder().build();
        try (Cursor objStyleCursor = briteDatabase.query("SELECT * FROM ObjectStyle WHERE uid = ?", uid)) {
            if (objStyleCursor != null && objStyleCursor.moveToFirst())
                objectStyle = ObjectStyle.create(objStyleCursor);
        }
        return objectStyle;
    }

    private ArrayList<FieldViewModel> renderOptions(FieldViewModel fieldViewModel, ProgramStageSectionRenderingType renderingType) {
        ArrayList<FieldViewModel> renderList = new ArrayList<>();
        try (Cursor cursor = briteDatabase.query(OPTIONS, fieldViewModel.optionSet() == null ? "" : fieldViewModel.optionSet())) {
            if (cursor != null && cursor.moveToFirst()) {
                int optionCount = cursor.getCount();
                for (int i = 0; i < optionCount; i++) {
                    String uid = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    String optionCode = cursor.getString(2);
                    ValueTypeDeviceRendering fieldRendering = getFieldRendering(uid);
                    ObjectStyle objectStyle = getObjectStyle(uid);

                    renderList.add(fieldFactory.create(
                            fieldViewModel.uid() + "." + uid, //fist
                            displayName + "-" + optionCode, ValueType.TEXT, false,
                            fieldViewModel.optionSet(), fieldViewModel.value(), fieldViewModel.programStageSection(),
                            fieldViewModel.allowFutureDate(),
                            fieldViewModel.editable() != null && fieldViewModel.editable(),
                            renderingType, fieldViewModel.description(), fieldRendering, optionCount, objectStyle));

                    cursor.moveToNext();
                }
            }
        }
        return renderList;
    }

    private List<FieldViewModel> checkRenderType(List<FieldViewModel> fieldViewModels) {

        ArrayList<FieldViewModel> renderList = new ArrayList<>();

        for (FieldViewModel fieldViewModel : fieldViewModels) {
            ProgramStageSectionRenderingType renderingType = renderingType(fieldViewModel.programStageSection());
            if (!isEmpty(fieldViewModel.optionSet()) && renderingType != ProgramStageSectionRenderingType.LISTING) {
                renderList.addAll(renderOptions(fieldViewModel, renderingType));

            } else {
                renderList.add(fieldViewModel);
            }
        }
        return renderList;
    }

    @NonNull
    @Override
    public Flowable<List<FieldViewModel>> list() {
        accessDataWrite = getAccessDataWrite();
        return briteDatabase
                .createQuery(SqlConstants.TEI_DATA_VALUE_TABLE, prepareStatement(eventUid))
                .mapToList(this::transform)
                .map(this::checkRenderType)
                .toFlowable(BackpressureStrategy.LATEST);
    }


    @NonNull
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private String prepareStatement(String sectionUid, String eventUid) {
        String where;
        if (isEmpty(sectionUid)) {
            where = String.format(Locale.US, "WHERE Event.uid = '%s'", eventUid == null ? "" : eventUid);
        } else {
            where = String.format(Locale.US, "WHERE Event.uid = '%s' AND " +
                    "Field.section = '%s'", eventUid == null ? "" : eventUid, sectionUid);
        }

        return String.format(Locale.US, QUERY, where);
    }

    @NonNull
    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private String prepareStatement(String eventUid) {
        StringBuilder sectionUids = getSectionUids();
        return String.format(Locale.US, QUERY, getWhereStatement(sectionUids));
    }

    private StringBuilder getSectionUids(){
        StringBuilder sectionUids = new StringBuilder();
        try (Cursor sectionsCursor = briteDatabase.query(SELECT_SECTIONS, eventUid)) {
            if (sectionsCursor != null && sectionsCursor.moveToFirst()) {
                for (int i = 0; i < sectionsCursor.getCount(); i++) {
                    if (sectionsCursor.getString(2) != null)
                        sectionUids.append(String.format("'%s'", sectionsCursor.getString(2)));
                    if (i < sectionsCursor.getCount() - 1)
                        sectionUids.append(",");
                    sectionsCursor.moveToNext();
                }
            }
        }
        return sectionUids;
    }

    private String getWhereStatement(StringBuilder sectionUids) {
        String where;
        if (isEmpty(sectionUids)) {
            where = String.format(Locale.US, "WHERE Event.uid = '%s'", eventUid == null ? "" : eventUid);
        } else {
            where = String.format(Locale.US, "WHERE Event.uid = '%s' AND " +
                    "Field.section IN (%s)", eventUid == null ? "" : eventUid, sectionUids);
        }
        return where;
    }

    @NonNull
    private FieldViewModel transform(@NonNull Cursor cursor) {
        String uid = cursor.getString(0);
        String displayName = cursor.getString(1);
        String valueTypeName = cursor.getString(2);
        boolean mandatory = cursor.getInt(3) == 1;
        String optionSet = cursor.getString(4);
        String dataValue = cursor.getString(5);
        String optionCodeName = cursor.getString(6);
        String programStageSection = cursor.getString(7);
        boolean allowFurureDates = cursor.getInt(8) == 1;
        EventStatus eventStatus = EventStatus.valueOf(cursor.getString(9));
        String formName = cursor.getString(10);
        String description = cursor.getString(11);

        if (!isEmpty(optionCodeName)) {
            dataValue = optionCodeName;
        }

        int optionCount = 0;
        if (!isEmpty(optionSet)) {
            try (Cursor countCursor = briteDatabase.query("SELECT COUNT (uid) FROM Option WHERE optionSet = ?", optionSet)) {
                if (countCursor != null && countCursor.moveToFirst())
                    optionCount = countCursor.getInt(0);
            }
        }

        ValueTypeDeviceRendering fieldRendering = null;
        try (Cursor rendering = briteDatabase.query("SELECT ValueTypeDeviceRendering.* FROM ValueTypeDeviceRendering" +
                " JOIN ProgramStageDataElement ON ProgramStageDataElement.uid = ValueTypeDeviceRendering.uid" +
                " WHERE ProgramStageDataElement.dataElement = ? LIMIT 1", uid)) {
            if (rendering != null && rendering.moveToFirst())
                fieldRendering = ValueTypeDeviceRendering.create(rendering);
        }
        ObjectStyle objectStyle = ObjectStyle.builder().build();
        try (Cursor objStyleCursor = briteDatabase.query("SELECT * FROM ObjectStyle WHERE uid = ?", uid)) {
            if (objStyleCursor != null && objStyleCursor.moveToFirst())
                objectStyle = ObjectStyle.create(objStyleCursor);
        }

        ProgramStageSectionRenderingType renderingType = renderingType(programStageSection);

        return fieldFactory.create(uid, formName == null ? displayName : formName,
                ValueType.valueOf(valueTypeName), mandatory, optionSet, dataValue,
                programStageSection, allowFurureDates,
                isEnrollmentOpen() && eventStatus == EventStatus.ACTIVE && accessDataWrite,
                renderingType, description, fieldRendering, optionCount, objectStyle);
    }

    @NonNull
    @Override
    public Flowable<Result<RuleEffect>> calculate() {
        return queryDataValues(eventUid)
                .map(dataValues -> eventBuilder.dataValues(dataValues).build())
                .switchMap(
                        event -> formRepository.ruleEngine()
                                .switchMap(ruleEngine -> {
                                    if (isEmpty(lastUpdatedUid))
                                        return Flowable.fromCallable(ruleEngine.evaluate(event));
                                    else
                                        return Flowable.just(dataElementRules.get(lastUpdatedUid) != null ? dataElementRules.get(lastUpdatedUid) : new ArrayList<Rule>())
                                                .flatMap(rules -> {
                                                    if (!rules.isEmpty())
                                                        return Flowable.fromCallable(ruleEngine.evaluate(event, rules));
                                                    else
                                                        return Flowable.just(new ArrayList<RuleEffect>());
                                                });
                                })
                                .map(Result::success)
                                .onErrorReturn(error -> Result.failure(new Exception(error)))

                );
    }

    @Override
    public Observable<Boolean> completeEvent() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SqlConstants.EVENT_STATUS, EventStatus.COMPLETED.name());
        String completeDate = DateUtils.databaseDateFormat().format(DateUtils.getInstance().getToday());
        contentValues.put(SqlConstants.EVENT_COMPLETE_DATE, completeDate);
        return Observable.just(briteDatabase.update(SqlConstants.EVENT_TABLE, contentValues, UID_EQUALS_QUESTION_MARK, eventUid) > 0);
    }

    @Override
    public boolean reopenEvent() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SqlConstants.EVENT_STATUS, EventStatus.ACTIVE.name());
        return briteDatabase.update(SqlConstants.EVENT_TABLE, contentValues, UID_EQUALS_QUESTION_MARK, eventUid) > 0;
    }

    @Override
    public Observable<Boolean> deleteEvent() {
        Cursor eventCursor = briteDatabase.query("SELECT Event.* FROM Event WHERE Event.uid = ?", eventUid);
        long status = -1;
        if (eventCursor != null && eventCursor.moveToNext()) {
            Event eventModel = Event.create(eventCursor);
            if (eventModel.state() == State.TO_POST) {
                String deleteWhere = String.format(
                        "%s.%s = ?",
                        SqlConstants.EVENT_TABLE, SqlConstants.EVENT_UID
                );
                status = briteDatabase.delete(SqlConstants.EVENT_TABLE, deleteWhere, eventUid);
            } else {
                ContentValues contentValues = eventModel.toContentValues();
                contentValues.put(SqlConstants.EVENT_STATE, State.TO_DELETE.name());
                status = briteDatabase.update(SqlConstants.EVENT_TABLE, contentValues, SqlConstants.EVENT_UID + " = ?", eventUid);
            }
            if (status == 1 && eventModel.enrollment() != null)
                updateEnrollment(eventModel.enrollment());
        }
        return Observable.just(status == 1);
    }

    private void updateEnrollment(String enrollmentUid) {
        String selectEnrollment = "SELECT *\n" +
                "FROM Enrollment\n" +
                "WHERE uid = ? LIMIT 1;";
        try (Cursor enrollmentCursor = briteDatabase.query(selectEnrollment, enrollmentUid)) {
            if (enrollmentCursor != null && enrollmentCursor.moveToFirst()) {
                Enrollment enrollmentModel = Enrollment.create(enrollmentCursor);

                ContentValues cv = enrollmentModel.toContentValues();
                cv.put(SqlConstants.ENROLLMENT_LAST_UPDATED, DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime()));
                cv.put(SqlConstants.ENROLLMENT_STATE, enrollmentModel.state() == State.TO_POST ? State.TO_POST.name() : State.TO_UPDATE.name());
                briteDatabase.update(SqlConstants.ENROLLMENT_TABLE, cv, UID_EQUALS_QUESTION_MARK, enrollmentUid);
                updateTei(enrollmentModel.trackedEntityInstance());
            }
        }
    }

    private void updateTei(String teiUid) {
        String selectTei = "SELECT * FROM TrackedEntityInstance WHERE uid = ?";
        try (Cursor teiCursor = briteDatabase.query(selectTei, teiUid)) {
            if (teiCursor != null && teiCursor.moveToFirst()) {
                TrackedEntityInstance teiModel = TrackedEntityInstance.create(teiCursor);
                ContentValues cv = teiModel.toContentValues();
                cv.put(SqlConstants.TEI_LAST_UPDATED, DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime()));
                cv.put(SqlConstants.TEI_STATE,
                        teiModel.state() == State.TO_POST ? State.TO_POST.name() : State.TO_UPDATE.name());
                briteDatabase.update(SqlConstants.TEI_TABLE, cv, UID_EQUALS_QUESTION_MARK, teiUid);
            }
        }
    }

    @Override
    public Observable<Boolean> updateEventStatus(EventStatus
                                                         status) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SqlConstants.EVENT_STATUS, status.name());
        String updateDate = DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime());
        contentValues.put(SqlConstants.EVENT_LAST_UPDATED, updateDate);
        return Observable.just(briteDatabase.update(SqlConstants.EVENT_TABLE, contentValues, UID_EQUALS_QUESTION_MARK, eventUid) > 0);
    }

    @Override
    public Observable<Boolean> rescheduleEvent(Date newDate) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SqlConstants.EVENT_DUE_DATE, DateUtils.databaseDateFormat().format(newDate));
        return Observable.just(briteDatabase.update(SqlConstants.EVENT_TABLE, contentValues, UID_EQUALS_QUESTION_MARK, eventUid))
                .flatMap(result -> updateEventStatus(EventStatus.SCHEDULE));
    }

    @Override
    public Observable<String> programStage() {
        return Observable.defer(() -> Observable.just(d2.eventModule().events.uid(eventUid).get().programStage()));
    }

    @Override
    public boolean getAccessDataWrite() {
        boolean canWrite;
        canWrite =
                d2.programModule().programs.uid(
                        d2.eventModule().events.uid(eventUid).get().program()
                ).get().access().data().write();
        if (canWrite)
            canWrite =
                    d2.programModule().programStages.uid(
                            d2.eventModule().events.uid(eventUid).get().programStage()
                    ).get().access().data().write();
        return canWrite;
    }

    @Override
    public void setLastUpdated(String lastUpdatedUid) {
        this.lastUpdatedUid = lastUpdatedUid;
    }

    @Override
    public Flowable<EventStatus> eventStatus() {
        return Flowable.just(d2.eventModule().events.uid(eventUid).get())
                .map(Event::status);
    }

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
            " WHERE Event.uid = ? AND value IS NOT NULL AND " + SqlConstants.EVENT_TABLE + "." + SqlConstants.EVENT_STATE + NOT_EQUALS + QUOTE + State.TO_DELETE + "';";

    @NonNull
    private Flowable<List<RuleDataValue>> queryDataValues(String eventUid) {
        return briteDatabase.createQuery(Arrays.asList(SqlConstants.EVENT_TABLE,
                SqlConstants.TEI_DATA_VALUE_TABLE), QUERY_VALUES, eventUid == null ? "" : eventUid)
                .mapToList(cursor -> {
                    Date eventDate = parseDate(cursor.getString(0));
                    String programStage = cursor.getString(1);
                    String dataElement = cursor.getString(2);
                    String value = cursor.getString(3);
                    boolean useCode = cursor.getInt(4) == 1;
                    String optionCode = cursor.getString(5);
                    String optionName = cursor.getString(6);
                    if (!isEmpty(optionCode) && !isEmpty(optionName))
                        value = useCode ? optionCode : optionName; //If de has optionSet then check if value should be code or name for program rules
                    return RuleDataValue.create(eventDate, programStage,
                            dataElement, value);
                }).toFlowable(BackpressureStrategy.LATEST);
    }

    @NonNull
    private static Date parseDate(@NonNull String date) throws ParseException {
        return BaseIdentifiableObject.DATE_FORMAT.parse(date);
    }

    @NonNull
    private FormSectionViewModel mapToFormSectionViewModels
            (@NonNull Cursor cursor) {
        // GET PROGRAMSTAGE DISPLAYNAME IN CASE THERE ARE NO SECTIONS
        if (cursor.getString(2) == null) {
            // This programstage has no sections
            return FormSectionViewModel.createForProgramStageWithLabel(eventUid, cursor.getString(4), cursor.getString(1));
        } else {
            // This programstage has sections
            return FormSectionViewModel.createForSection(eventUid, cursor.getString(2), cursor.getString(3), cursor.getString(5));
        }
    }
}
