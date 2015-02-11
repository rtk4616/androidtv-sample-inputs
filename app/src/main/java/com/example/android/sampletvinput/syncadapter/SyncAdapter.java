/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sampletvinput.syncadapter;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;

import com.example.android.sampletvinput.BaseTvInputService.ChannelInfo;
import com.example.android.sampletvinput.BaseTvInputService.ProgramInfo;
import com.example.android.sampletvinput.TvContractUtils;
import com.example.android.sampletvinput.rich.RichTvInputService;

import java.util.ArrayList;
import java.util.List;

/**
 * A SyncAdapter implementation which updates program info periodically.
 */
class SyncAdapter extends AbstractThreadedSyncAdapter {
    public static final String TAG = "SyncAdapter";

    public static final String BUNDLE_KEY_INPUT_ID = "bundle_key_input_id";
    public static final long SYNC_FREQUENCY_SEC = 60 * 60 * 24;  // 1 day
    private static final int SYNC_WINDOW_SEC = 60 * 60 * 24 * 2;  // 2 days
    private static final int BATCH_OPERATION_COUNT = 100;

    private final Context mContext;

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = context;
    }

    public SyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        mContext = context;
    }

    /**
     * Called periodically by the system in every {@code SYNC_FREQUENCY_SEC}.
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
            ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "onPerformSync(" + account + ", " + authority + ", " + extras + ")");
        String inputId = extras.getString(SyncAdapter.BUNDLE_KEY_INPUT_ID);
        if (inputId == null) {
            return;
        }
        List<ChannelInfo> channels = RichTvInputService.createRichChannelsStatic(mContext);
        LongSparseArray<ChannelInfo> channelMap = TvContractUtils.buildChannelMap(
                mContext.getContentResolver(), inputId, channels);
        for (int i = 0; i < channelMap.size(); ++i) {
            Uri channelUri = TvContract.buildChannelUri(channelMap.keyAt(i));
            insertPrograms(channelUri, channelMap.valueAt(i));
        }
    }

    /**
     * Inserts programs from now to {@link SyncAdapter#SYNC_WINDOW_SEC}.
     *
     * @param channelUri The channel where the program info will be added.
     * @param channelInfo {@link ChannelInfo} instance which includes program information.
     */
    private void insertPrograms(Uri channelUri, ChannelInfo channelInfo) {
        long durationSumSec = 0;
        List<ContentValues> programs = new ArrayList<>();
        for (ProgramInfo program : channelInfo.mPrograms) {
            durationSumSec += program.mDurationSec;

            ContentValues values = new ContentValues();
            values.put(TvContract.Programs.COLUMN_CHANNEL_ID, ContentUris.parseId(channelUri));
            values.put(TvContract.Programs.COLUMN_TITLE, program.mTitle);
            values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, program.mDescription);
            values.put(TvContract.Programs.COLUMN_CONTENT_RATING,
                    TvContractUtils.contentRatingsToString(program.mContentRatings));
            if (!TextUtils.isEmpty(program.mPosterArtUri)) {
                values.put(TvContract.Programs.COLUMN_POSTER_ART_URI, program.mPosterArtUri);
            }
            programs.add(values);
        }

        long nowSec = System.currentTimeMillis() / 1000;
        long endSec = nowSec + SYNC_WINDOW_SEC;
        long updateStartTimeSec = nowSec - nowSec % durationSumSec;
        long nextPos = updateStartTimeSec;
        for (int i = 0; nextPos < endSec; ++i) {
            if (!TvContractUtils.hasProgramInfo(mContext.getContentResolver(), channelUri,
                    nextPos * 1000 + 1, (nextPos + durationSumSec) * 1000)) {
                long programStartSec = nextPos;
                ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                int programsCount = channelInfo.mPrograms.size();
                for (int j = 0; j < programsCount; ++j) {
                    ProgramInfo program = channelInfo.mPrograms.get(j);
                    ops.add(ContentProviderOperation.newInsert(
                            TvContract.Programs.CONTENT_URI)
                            .withValues(programs.get(j))
                            .withValue(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                                    programStartSec * 1000)
                            .withValue(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                                    (programStartSec + program.mDurationSec) * 1000)
                            .build());
                    programStartSec = programStartSec + program.mDurationSec;
                    if (j % BATCH_OPERATION_COUNT == BATCH_OPERATION_COUNT - 1
                            || j == programsCount - 1) {
                        try {
                            mContext.getContentResolver().applyBatch(TvContract.AUTHORITY, ops);
                        } catch (RemoteException | OperationApplicationException e) {
                            Log.e(TAG, "Failed to insert programs.", e);
                            return;
                        }
                        ops.clear();
                    }
                }
            }
            nextPos = updateStartTimeSec + i * durationSumSec;
        }
    }
}