package org.dhis2.usescases.main.program;

import org.hisp.dhis.android.core.organisationunit.OrganisationUnit;
import org.hisp.dhis.android.core.period.DatePeriod;

import java.util.List;

import androidx.annotation.NonNull;
import io.reactivex.Flowable;
import io.reactivex.Observable;

interface HomeRepository {

    @NonNull
    Flowable<List<ProgramViewModel>> programModels(List<DatePeriod> dateFilter, List<String> orgUnitFilter);

    @NonNull
    Observable<List<OrganisationUnit>> orgUnits(String parentUid);

    @NonNull
    Observable<List<OrganisationUnit>> orgUnits();

}