package org.dhis2.usescases.programStageSelection;

import androidx.annotation.NonNull;

import org.dhis2.data.dagger.PerActivity;
import org.dhis2.data.forms.RulesRepository;
import com.squareup.sqlbrite2.BriteDatabase;

import org.dhis2.utils.RulesUtilsProvider;
import org.hisp.dhis.rules.RuleExpressionEvaluator;

import dagger.Module;
import dagger.Provides;

/**
 * QUADRAM. Created by ppajuelo on 31/10/2017.
 */
@PerActivity
@Module
public class ProgramStageSelectionModule {

    private final String programUid;
    private final String enrollmentUid;
    private final String eventCreationType;

    public ProgramStageSelectionModule(String programId, String enrollmenId, String eventCreationType) {
        this.programUid = programId;
        this.enrollmentUid = enrollmenId;
        this.eventCreationType = eventCreationType;
    }

    @Provides
    @PerActivity
    ProgramStageSelectionContract.ProgramStageSelectionView providesView(@NonNull ProgramStageSelectionActivity activity) {
        return activity;
    }

    @Provides
    @PerActivity
    ProgramStageSelectionContract.ProgramStageSelectionPresenter providesPresenter(@NonNull ProgramStageSelectionRepository programStageSelectionRepository,
                                                                                   @NonNull RulesUtilsProvider ruleUtils) {
        return new ProgramStageSelectionPresenterImpl(programStageSelectionRepository,ruleUtils);
    }

    @Provides
    @PerActivity
    ProgramStageSelectionRepository providesProgramStageSelectionRepository(@NonNull BriteDatabase briteDatabase,
                                                                            @NonNull RuleExpressionEvaluator evaluator,
                                                                            RulesRepository rulesRepository) {
        return new ProgramStageSelectionRepositoryImpl(briteDatabase, evaluator, rulesRepository, programUid, enrollmentUid,eventCreationType);
    }

    @Provides
    @PerActivity
    RulesRepository rulesRepository(BriteDatabase briteDatabase) {
        return new RulesRepository(briteDatabase);
    }
}
