package com.expidevapps.android.measurements.support.v4.fragment;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.expidevapps.android.measurements.R;
import com.expidevapps.android.measurements.model.Church.Development;

import org.ccci.gto.android.common.app.AlertDialogCompat;

import java.util.EnumSet;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import butterknife.OnItemSelected;
import butterknife.Optional;

public abstract class BaseEditChurchDialogFragment extends DialogFragment {
    @Optional
    @Nullable
    @InjectView(R.id.title)
    TextView mTitleView;
    @Optional
    @Nullable
    @InjectView(R.id.icon)
    ImageView mIconView;

    @Optional
    @Nullable
    @InjectView(R.id.name)
    TextView mNameView;
    @Optional
    @Nullable
    @InjectView(R.id.contactName)
    TextView mContactNameView;
    @Optional
    @Nullable
    @InjectView(R.id.contactEmail)
    TextView mContactEmailView;
    @Optional
    @Nullable
    @InjectView(R.id.development)
    Spinner mDevelopmentSpinner;
    @Optional
    @Nullable
    @InjectView(R.id.size)
    TextView mSizeView;

    @Nullable
    ArrayAdapter<Development> mDevelopmentAdapter;

    /* BEGIN lifecycle */

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedState) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        AlertDialogCompat.setView(builder,getActivity(),R.layout.fragment_edit_church);
        return builder.create();
    }

    @Override
    public void onStart() {
        super.onStart();
        ButterKnife.inject(this, getDialog());
        setupViews();
    }

    protected void onChangeDevelopment(@NonNull final Development development) {
        updateIcon(development);
    }

    @Optional
    @OnClick(R.id.cancel)
    protected void onCancel() {
        this.dismiss();
    }

    @Override
    public void onStop() {
        super.onStop();
        cleanupViews();
        ButterKnife.reset(this);
    }

    /* END lifecycle */

    private void setupViews() {
        if (mDevelopmentSpinner != null) {
            // generate set of options
            final EnumSet<Development> types = EnumSet.allOf(Development.class);
            types.remove(Development.UNKNOWN);

            // generate Adapter for church types
            mDevelopmentAdapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item,
                                                     types.toArray(new Development[types.size()]));
            mDevelopmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            // attach adapter
            mDevelopmentSpinner.setAdapter(mDevelopmentAdapter);
        }
    }

    private void cleanupViews() {
        mDevelopmentAdapter = null;
    }

    @Optional
    @OnItemSelected(R.id.development)
    void changeDevelopment() {
        if (mDevelopmentSpinner != null) {
            final Object item = mDevelopmentSpinner.getSelectedItem();
            this.onChangeDevelopment(item instanceof Development ? (Development) item : Development.UNKNOWN);
        }
    }

    protected void updateTitle(@Nullable final String title) {
        if (mTitleView != null) {
            mTitleView.setText(title);
        }
    }

    protected void updateIcon(@NonNull final Development state) {
        if (mIconView != null) {
            mIconView.setImageResource(state.image);
        }
    }
}