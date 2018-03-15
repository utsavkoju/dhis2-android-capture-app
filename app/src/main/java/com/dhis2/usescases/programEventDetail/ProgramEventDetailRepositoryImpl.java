package com.dhis2.usescases.programEventDetail;

import android.support.annotation.NonNull;

import com.dhis2.utils.DateUtils;
import com.dhis2.utils.Period;
import com.squareup.sqlbrite2.BriteDatabase;

import org.hisp.dhis.android.core.category.CategoryComboModel;
import org.hisp.dhis.android.core.category.CategoryOptionComboModel;
import org.hisp.dhis.android.core.event.EventModel;
import org.hisp.dhis.android.core.organisationunit.OrganisationUnitModel;

import java.util.Date;
import java.util.List;

import io.reactivex.Observable;

/**
 * Created by ppajuelo on 02/11/2017.
 *
 */

public class ProgramEventDetailRepositoryImpl implements ProgramEventDetailRepository {

    private final BriteDatabase briteDatabase;

    ProgramEventDetailRepositoryImpl(BriteDatabase briteDatabase) {
        this.briteDatabase = briteDatabase;
    }

    @NonNull
    public Observable<List<EventModel>> programEvents(String programUid, String fromDate, String toDate) {
        String SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES = "SELECT * FROM " + EventModel.TABLE + " WHERE " + EventModel.Columns.PROGRAM + "='%s' AND " + EventModel.Columns.LAST_UPDATED + " BETWEEN '%s' and '%s'" ;
        return briteDatabase.createQuery(EventModel.TABLE, String.format(SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES, programUid, fromDate, toDate))
                .mapToList(EventModel::create);
    }

    @NonNull
    public Observable<List<EventModel>> programEvents(String programUid, List<Date> dates, Period period) {
        String SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES = "SELECT * FROM " + EventModel.TABLE + " WHERE " + EventModel.Columns.PROGRAM + "='%s' AND (%s)";
        StringBuilder dateQuery = new StringBuilder();
        String queryFormat = "(%s BETWEEN '%s' AND '%s') ";
        for (int i = 0; i < dates.size(); i++) {
            Date[] datesToQuery = DateUtils.getInstance().getDateFromDateAndPeriod(dates.get(i), period);
            dateQuery.append(String.format(queryFormat, EventModel.Columns.LAST_UPDATED, DateUtils.getInstance().formatDate(datesToQuery[0]), DateUtils.getInstance().formatDate(datesToQuery[1])));
            if (i < dates.size() - 1)
                dateQuery.append("OR ");
        }

        return briteDatabase.createQuery(EventModel.TABLE, String.format(SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES, programUid, dateQuery))
                .mapToList(EventModel::create);
    }

    @NonNull
    @Override
    public Observable<List<EventModel>> filteredProgramEvents(String programUid, String fromDate, String toDate, CategoryOptionComboModel categoryOptionComboModel) {
        if (categoryOptionComboModel == null){
            return programEvents(programUid, fromDate, toDate);
        }
        String SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES_AND_CAT_COMBO = "SELECT * FROM " + EventModel.TABLE + " WHERE " + EventModel.Columns.PROGRAM + "='%s' AND " + EventModel.Columns.LAST_UPDATED + " BETWEEN '%s' and '%s'"
                + " AND " + EventModel.Columns.ATTRIBUTE_OPTION_COMBO + "='%s'";
        return briteDatabase.createQuery(EventModel.TABLE, String.format(SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES_AND_CAT_COMBO, programUid, fromDate, toDate, categoryOptionComboModel.uid()))
                .mapToList(EventModel::create);
    }

    @NonNull
    @Override
    public Observable<List<EventModel>> filteredProgramEvents(String programUid, List<Date> dates, Period period, CategoryOptionComboModel categoryOptionComboModel) {
        if (categoryOptionComboModel == null){
            return programEvents(programUid, dates, period);
        }
        String SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES_AND_CAT_COMBO = "SELECT * FROM " + EventModel.TABLE + " WHERE " + EventModel.Columns.PROGRAM + "='%s' AND " + EventModel.Columns.ATTRIBUTE_OPTION_COMBO + "='%s' AND (%s)";
        StringBuilder dateQuery = new StringBuilder();
        String queryFormat = "(%s BETWEEN '%s' AND '%s') ";
        for (int i = 0; i < dates.size(); i++) {
            Date[] datesToQuery = DateUtils.getInstance().getDateFromDateAndPeriod(dates.get(i), period);
            dateQuery.append(String.format(queryFormat, EventModel.Columns.LAST_UPDATED, DateUtils.getInstance().formatDate(datesToQuery[0]), DateUtils.getInstance().formatDate(datesToQuery[1])));
            if (i < dates.size() - 1)
                dateQuery.append("OR ");
        }

        return briteDatabase.createQuery(EventModel.TABLE, String.format(SELECT_EVENT_WITH_PROGRAM_UID_AND_DATES_AND_CAT_COMBO, programUid, categoryOptionComboModel.uid(), dateQuery))
                .mapToList(EventModel::create);
    }

    @NonNull
    @Override
    public Observable<List<OrganisationUnitModel>> orgUnits() {
        String SELECT_ORG_UNITS = "SELECT * FROM " + OrganisationUnitModel.TABLE;
        return briteDatabase.createQuery(OrganisationUnitModel.TABLE, SELECT_ORG_UNITS)
                .mapToList(OrganisationUnitModel::create);
    }

    @NonNull
    @Override
    public Observable<List<CategoryOptionComboModel>> catCombo(String categoryComboUid) {
        String SELECT_CATEGORY_COMBO = "SELECT "+CategoryOptionComboModel.TABLE+".* FROM " + CategoryOptionComboModel.TABLE + " INNER JOIN " + CategoryComboModel.TABLE +
                " ON " + CategoryOptionComboModel.TABLE + "." + CategoryOptionComboModel.Columns.CATEGORY_COMBO + " = " + CategoryComboModel.TABLE + "." + CategoryComboModel.Columns.UID
                + " WHERE " + CategoryComboModel.TABLE + "." + CategoryComboModel.Columns.UID + " = '" + categoryComboUid + "'";
        return briteDatabase.createQuery(CategoryOptionComboModel.TABLE, SELECT_CATEGORY_COMBO)
                .mapToList(CategoryOptionComboModel::create);
    }
}