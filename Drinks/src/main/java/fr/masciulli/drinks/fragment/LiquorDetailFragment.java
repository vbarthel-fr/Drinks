package fr.masciulli.drinks.fragment;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;
import com.squareup.picasso.Transformation;

import java.util.ArrayList;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import butterknife.OnItemClick;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import fr.masciulli.android_quantizer.lib.ColorQuantizer;
import fr.masciulli.drinks.R;
import fr.masciulli.drinks.activity.DrinkDetailActivity;
import fr.masciulli.drinks.adapter.LiquorDetailAdapter;
import fr.masciulli.drinks.data.DrinksProvider;
import fr.masciulli.drinks.model.Drink;
import fr.masciulli.drinks.model.Liquor;
import fr.masciulli.drinks.util.AnimUtils;
import fr.masciulli.drinks.view.BlurTransformation;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class LiquorDetailFragment extends Fragment implements Callback<Liquor>, AbsListView.OnScrollListener {
    private static final int HEADERVIEWS_COUNT = 1;

    private static final long ANIM_IMAGE_ENTER_DURATION = 500;
    private static final long ANIM_TEXT_ENTER_DURATION = 500;

    private static final TimeInterpolator sDecelerator = new DecelerateInterpolator();

    @InjectView(R.id.image)
    ImageView mImageView;
    @InjectView(R.id.image_blurred)
    ImageView mBlurredImageView;
    @InjectView(R.id.history)
    TextView mHistoryView;
    @InjectView(R.id.progressbar)
    ProgressBar mProgressBar;
    @InjectView(R.id.wikipedia)
    Button mWikipediaButton;
    @InjectView(R.id.drinks_title)
    TextView mDrinksTitleView;
    @InjectView(R.id.color1)
    View mColorView1;
    @InjectView(R.id.color2)
    View mColorView2;
    @InjectView(R.id.color3)
    View mColorView3;
    @InjectView(R.id.color4)
    View mColorView4;

    private ListView mListView;
    private View mHeaderView;

    private Target mTarget = new Target() {
        @Override
        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
            mImageView.setImageBitmap(bitmap);
            new QuantizeBitmapTask().execute(bitmap);
        }

        @Override
        public void onBitmapFailed(Drawable errorDrawable) {

        }

        @Override
        public void onPrepareLoad(Drawable placeHolderDrawable) {

        }
    };

    private LiquorDetailAdapter mDrinkAdapter;

    private DrinksOnScrollListener mAnimationOnScrollListener;

    private MenuItem mRetryAction;

    private int mLiquorId;
    private Transformation mTransformation;
    private Liquor mLiquor;

    private boolean mDualPane;

    private int mImageViewHeight;

    private int mTopDelta;
    private int mPreviousItemTop;
    private Drawable mBackground;
    private long mPreviousItemHeight;
    private int mPreviousOrientation;

    private Callback<List<Drink>> mDrinksCallback = new Callback<List<Drink>>() {

        /**
         * Retrofit callback when drinks loaded
         * @param drinks
         * @param response
         */
        @Override
        public void success(List<Drink> drinks, Response response) {
            Log.d(getTag(), "Liquor detail related drinks loading/retrieving has succeeded");

            if (getActivity() == null) return;

            List<Drink> filteredDrinks = new ArrayList<Drink>();

            for (Drink drink : drinks) {
                for (String ingredient : drink.ingredients) {
                    if (ingredient.toLowerCase().contains(mLiquor.name.toLowerCase())) {
                        filteredDrinks.add(drink);
                        break;
                    }

                    //At this point, we know main name does not match
                    for (String otherName : mLiquor.otherNames) {
                        if (ingredient.toLowerCase().contains(otherName.toLowerCase())) {
                            filteredDrinks.add(drink);
                            break;
                        }
                    }

                }
            }

            if (filteredDrinks.size() > 0) {
                mDrinksTitleView.setVisibility(View.VISIBLE);
            } else {
                mDrinksTitleView.setVisibility(View.GONE);
            }

            mDrinkAdapter.update(filteredDrinks);
        }

        @Override
        public void failure(RetrofitError error) {

            Response resp = error.getResponse();
            String message;
            if (resp != null) {
                message = "response status : " + resp.getStatus();
            } else {
                message = "no response";
            }
            Log.e(getTag(), "Liquor detail related drinks loading has failed : " + message);

            if (getActivity() == null) return;

            if (error.isNetworkError()) {
                Crouton.makeText(getActivity(), getString(R.string.network_error), Style.ALERT).show();
            } else {
                Crouton.makeText(getActivity(), R.string.liquor_detail_drinks_loading_failed, Style.ALERT).show();
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_liquor_detail, container, false);

        setHasOptionsMenu(true);

        mDualPane = getResources().getBoolean(R.bool.dualpane);

        mHeaderView = inflater.inflate(R.layout.header_liquor_detail, null);

        mListView = ButterKnife.findById(root, R.id.list);
        mListView.addHeaderView(mHeaderView);

        //View injection only done here because header has to be added before
        ButterKnife.inject(this, root);

        mDrinkAdapter = new LiquorDetailAdapter(getActivity());
        mListView.setAdapter(mDrinkAdapter);

        Intent intent = getActivity().getIntent();
        mLiquorId = intent.getIntExtra("liquor_id", 1);
        String name = intent.getStringExtra("liquor_name");
        String imageUrl = intent.getStringExtra("liquor_imageurl");

        // Data needed for animations
        mPreviousItemHeight = intent.getIntExtra("height", 0);
        mPreviousItemTop = intent.getIntExtra("top", 0);
        mPreviousOrientation = intent.getIntExtra("orientation", 0);

        mBackground = root.getBackground();

        getActivity().setTitle(name);
        Picasso.with(getActivity()).load(imageUrl).into(mTarget);

        mTransformation = new BlurTransformation(getActivity(), getResources().getInteger(R.integer.blur_radius));
        Picasso.with(getActivity()).load(imageUrl).transform(mTransformation).into(mBlurredImageView);

        mImageViewHeight = (int) getResources().getDimension(R.dimen.liquor_detail_recipe_margin);
        mListView.setOnScrollListener(this);
        mAnimationOnScrollListener = new DrinksOnScrollListener(mListView);

        if (savedInstanceState != null) {
            Liquor liquor = savedInstanceState.getParcelable("liquor");
            List<Drink> drinks = savedInstanceState.getParcelableArrayList("drinks");
            if (liquor != null && drinks != null) {
                onLiquorFound(liquor);
                mDrinksCallback.success(drinks, null);
            } else {
                refresh();
            }
        } else {
            if (!mDualPane) {
                ViewTreeObserver observer = mImageView.getViewTreeObserver();
                if (observer != null) {
                    observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {

                        @Override
                        public boolean onPreDraw() {
                            mImageView.getViewTreeObserver().removeOnPreDrawListener(this);

                            int[] screenLocation = new int[2];
                            mImageView.getLocationOnScreen(screenLocation);
                            mTopDelta = mPreviousItemTop - screenLocation[1];

                            runEnterAnimation();

                            return true;
                        }
                    });
                }
            } else {
                refresh();
            }
        }

        return root;
    }

    @OnClick(R.id.wikipedia)
    void goToWikipedia() {
        if (mLiquor == null) return;
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mLiquor.wikipedia)));
    }

    @OnItemClick(R.id.list)
    void openRelatedDrinkDetail(int position, View view) {
        Drink drink = mDrinkAdapter.getItem(position - HEADERVIEWS_COUNT);

        // Data needed for animations in sub activity
        int[] screenLocation = new int[2];
        view.getLocationOnScreen(screenLocation);
        int orientation = getResources().getConfiguration().orientation;

        Intent intent = new Intent(getActivity(), DrinkDetailActivity.class);
        intent.
                putExtra("drink_name", drink.name).
                putExtra("drink_imageurl", drink.imageUrl).
                putExtra("drink_id", drink.id).
                putExtra("top", screenLocation[1]).
                putExtra("height", view.getHeight()).
                putExtra("orientation", orientation);
        startActivity(intent);
    }

    private void runEnterAnimation() {

        Runnable refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        };

        mImageView.setTranslationY(-mImageView.getHeight());

        ViewPropertyAnimator animator = mImageView.animate().setDuration(ANIM_IMAGE_ENTER_DURATION).
                translationY(0).
                setInterpolator(sDecelerator);

        AnimUtils.scheduleEndAction(animator, refreshRunnable, ANIM_IMAGE_ENTER_DURATION);

        ObjectAnimator bgAnim = ObjectAnimator.ofInt(mBackground, "alpha", 0, 255);
        bgAnim.setDuration(ANIM_IMAGE_ENTER_DURATION);
        bgAnim.start();

    }

    private void refresh() {
        mProgressBar.setVisibility(View.VISIBLE);
        if (mRetryAction != null) mRetryAction.setVisible(false);
        DrinksProvider.getLiquor(mLiquorId, this);
    }


    public void onLiquorFound(Liquor liquor) {
        mLiquor = liquor;
        if (getActivity() == null) return;

        mProgressBar.setVisibility(View.GONE);

        getActivity().setTitle(liquor.name);

        Picasso.with(getActivity()).load(liquor.imageUrl).into(mImageView);
        Picasso.with(getActivity()).load(liquor.imageUrl).transform(mTransformation).into(mBlurredImageView);

        mHistoryView.setText(liquor.history);
        mWikipediaButton.setText(String.format(getString(R.string.liquor_detail_wikipedia), liquor.name));
        mDrinksTitleView.setText(String.format(getString(R.string.liquor_detail_drinks), liquor.name));

        ViewTreeObserver observer = mListView.getViewTreeObserver();
        if (observer != null) {
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {

                @Override
                public boolean onPreDraw() {
                    mListView.getViewTreeObserver().removeOnPreDrawListener(this);
                    mListView.setAlpha(0);
                    mListView.animate().setDuration(ANIM_TEXT_ENTER_DURATION).
                            alpha(1).
                            setInterpolator(sDecelerator);

                    return true;
                }
            });
        }
        mListView.setVisibility(View.VISIBLE);
    }

    /**
     * Retrofit callback when liquor loaded
     *
     * @param liquor
     * @param response
     */
    @Override
    public void success(Liquor liquor, Response response) {
        if (getActivity() == null) return;

        DrinksProvider.getAllDrinks(mDrinksCallback);
        onLiquorFound(liquor);
    }

    @Override
    public void failure(RetrofitError error) {
        if (getActivity() == null) return;

        mProgressBar.setVisibility(View.GONE);
        if (mRetryAction != null) mRetryAction.setVisible(true);
        if (error.isNetworkError()) {
            Crouton.makeText(getActivity(), getString(R.string.network_error), Style.ALERT).show();
        } else {
            Crouton.makeText(getActivity(), R.string.liquor_detail_loading_failed, Style.ALERT).show();
        }

    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.drink_detail, menu);
        mRetryAction = menu.findItem(R.id.retry);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().finish();
                return true;
            case R.id.retry:
                refresh();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onScrollStateChanged(AbsListView listView, int state) {
        if (mAnimationOnScrollListener != null) {
            mAnimationOnScrollListener.onScrollStateChanged(listView, state);
        }
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (mAnimationOnScrollListener != null) {
            mAnimationOnScrollListener.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount);
        }
        float alpha = 2 * (float) -mHeaderView.getTop() / (float) mImageViewHeight;
        if (alpha > 1) {
            alpha = 1;
        }
        mBlurredImageView.setAlpha(alpha);

        mImageView.setTop(mHeaderView.getTop() / 2);
        mImageView.setBottom(mImageViewHeight + mHeaderView.getTop());
        mBlurredImageView.setTop(mHeaderView.getTop() / 2);
        mBlurredImageView.setBottom(mImageViewHeight + mHeaderView.getTop());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // TODO do not use getCount
        if (mLiquor != null && mDrinkAdapter.getCount() > 0) {
            outState.putParcelable("liquor", mLiquor);
            outState.putParcelableArrayList("drinks", mDrinkAdapter.getDrinks());
        }
    }

    private class QuantizeBitmapTask extends AsyncTask<Bitmap, Void, ArrayList<Integer>> {
        @Override
        protected ArrayList<Integer> doInBackground(Bitmap... bitmaps) {

            Bitmap originalBitmap = bitmaps[0];

            int originalWidth = originalBitmap.getWidth();
            int originalHeight = originalBitmap.getHeight();

            Bitmap bitmap = Bitmap.createScaledBitmap(originalBitmap, originalWidth / 16, originalHeight / 16, true);

            Log.d(getTag(), bitmap.getWidth() + " " + bitmap.getHeight());

            ArrayList<Integer> quantizedColors = new ColorQuantizer().load(bitmap).quantize().getQuantizedColors();

            //TODO figure out why only two colors for some images

            return quantizedColors;
        }

        @Override
        protected void onPostExecute(ArrayList<Integer> colors) {
            View[] colorViews = new View[]{mColorView1, mColorView2, mColorView3, mColorView4};
            for (int i = 0; i < colors.size() && i < 4; i++) {
                colorViews[i].setBackgroundColor(colors.get(i));
            }
        }
    }
}
