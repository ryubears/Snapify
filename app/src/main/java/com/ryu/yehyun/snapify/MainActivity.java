package com.ryu.yehyun.snapify;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.snapchat.kit.sdk.SnapCreative;
import com.snapchat.kit.sdk.creative.api.SnapCreativeKitApi;
import com.snapchat.kit.sdk.creative.exceptions.SnapMediaSizeException;
import com.snapchat.kit.sdk.creative.exceptions.SnapStickerSizeException;
import com.snapchat.kit.sdk.creative.media.SnapMediaFactory;
import com.snapchat.kit.sdk.creative.media.SnapPhotoFile;
import com.snapchat.kit.sdk.creative.media.SnapSticker;
import com.snapchat.kit.sdk.creative.models.SnapContent;
import com.snapchat.kit.sdk.creative.models.SnapLiveCameraContent;
import com.snapchat.kit.sdk.creative.models.SnapPhotoContent;
import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.PlayerApi;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp;
import com.spotify.android.appremote.api.error.NotLoggedInException;
import com.spotify.android.appremote.api.error.UserNotAuthorizedException;
import com.spotify.protocol.client.CallResult;
import com.spotify.protocol.types.Artist;
import com.spotify.protocol.types.Image;
import com.spotify.protocol.types.ImageUri;
import com.spotify.protocol.types.Track;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.hdodenhof.circleimageview.CircleImageView;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final String SPOTIFY_CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final int SPOTIFY_AUTH_REQUEST_CODE = 4827;
    private static final String SPOTIFY_REDIRECT_URI = "https://www.google.com/";
    private static final String[] SPOTIFY_SCOPE = new String[] {
            // User saved music and playlists.
            "user-library-read",
            "playlist-read-private",
            // User playback and streaming.
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-recently-played",
            "user-read-currently-playing",
            "app-remote-control",
            "streaming",
            // User profile.
            "user-read-birthdate",
            "user-read-email",
            "user-read-private",
            "user-top-read",
    };
    private static final String SPOTIFY_LOGIN_KEY = "spotify-login-key";
    private static final int PROGRESS_BAR_TIME_UNIT = 10000;

    private ImageButton snapchatButton;
    private ConstraintLayout player;
    private ProgressBar playerProgressBar;
    private CircleImageView playerAlbumCoverImageView;
    private TextView trackTitleTextView;
    private TextView trackArtistsTextView;
    private ImageButton playerControlButton;

    private SharedPreferences sharedPreferences;
    private SpotifyAppRemote spotifyAppRemote;
    private Timer progressBarTimer;
    private Toast toast;

    private Track track;
    private Bitmap albumCoverBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupViews();

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!sharedPreferences.getBoolean(SPOTIFY_LOGIN_KEY, false)) {
            loginSpotify();
        } else {
            connectSpotify();
        }
    }

    @Override
    protected void onDestroy() {
        SpotifyAppRemote.disconnect(spotifyAppRemote);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle Spotify Auth response.
        if (requestCode == SPOTIFY_AUTH_REQUEST_CODE) {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, data);
            switch (response.getType()) {
                case TOKEN:
                    Log.d(LOG_TAG, "Login Success");
                    sharedPreferences.edit().putBoolean(SPOTIFY_LOGIN_KEY, true).apply();
                    connectSpotify();
                    break;
                case ERROR:
                    Log.e(LOG_TAG, "Login Error");
                    sharedPreferences.edit().putBoolean(SPOTIFY_LOGIN_KEY, false).apply();
                    break;
                default:
                    Log.e(LOG_TAG, "Login Cancelled");
                    sharedPreferences.edit().putBoolean(SPOTIFY_LOGIN_KEY, false).apply();
            }
        }
    }

    // Bind views.
    private void setupViews() {
        snapchatButton = findViewById(R.id.main_snapchat_button);
        player = findViewById(R.id.main_player);
        playerProgressBar = findViewById(R.id.player_progress_bar);
        playerAlbumCoverImageView = findViewById(R.id.player_album_cover);
        trackTitleTextView = findViewById(R.id.track_title);
        trackArtistsTextView = findViewById(R.id.track_artists);
        playerControlButton = findViewById(R.id.player_control_button);

        // Set focus on track TextViews to enable marquee scrolling.
        trackTitleTextView.setSelected(true);
        trackArtistsTextView.setSelected(true);
    }

    private void sendSnapchat() {
        if (track == null || albumCoverBitmap == null) {
            if (toast != null) toast.cancel();
            toast = Toast.makeText(this, "Waiting for track data. Retry again.", Toast.LENGTH_SHORT);
            toast.show();
            return;
        }

        SnapCreativeKitApi snapCreativeKitApi = SnapCreative.getApi(this);

        SnapLiveCameraContent snapLiveCameraContent = new SnapLiveCameraContent();
        snapLiveCameraContent.setCaptionText(getCaptionText());
        snapLiveCameraContent.setAttachmentUrl(track.uri);

        SnapMediaFactory snapMediaFactory = SnapCreative.getMediaFactory(this);
        SnapSticker snapSticker;
        try {
            File stickerFile = createStickerFile();
            if (stickerFile != null) {
                snapSticker = snapMediaFactory.getSnapStickerFromFile(stickerFile);
                snapSticker.setWidth(300);
                snapSticker.setHeight(300);
                snapSticker.setPosX(0.125f);
                snapSticker.setPosY(0.5f);
                snapSticker.setRotationDegreesClockwise(0);
                snapLiveCameraContent.setSnapSticker(snapSticker);
            }
        } catch (SnapStickerSizeException e) {
            Log.e(LOG_TAG, e.getLocalizedMessage());
            return;
        }

        snapCreativeKitApi.send(snapLiveCameraContent);
    }

    private File createStickerFile() {
        File file = null;

        try {
            file = new File(this.getCacheDir(), "sticker");
            if (file.exists()) {
                boolean deleteSuccess = file.delete();
                if (!deleteSuccess) {
                    Log.e(LOG_TAG, "Unable to delete sticker file.");
                    return null;
                }
            }
            boolean isSuccess = file.createNewFile();
            if (!isSuccess) {
                Log.e(LOG_TAG, "Unable to create file for sticker.");
                return null;
            }

            ByteArrayOutputStream byteOutputStream= new ByteArrayOutputStream();
            albumCoverBitmap.compress(Bitmap.CompressFormat.PNG, 0, byteOutputStream);
            byte[] bitmapData = byteOutputStream.toByteArray();

            FileOutputStream fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(bitmapData);
            fileOutputStream.flush();
            fileOutputStream.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, e.getLocalizedMessage());
        }

        return file;
    }

    // Request result is handled in onActivityResult.
    private void loginSpotify() {
        AuthenticationRequest.Builder builder = new AuthenticationRequest.Builder(
                SPOTIFY_CLIENT_ID,
                AuthenticationResponse.Type.TOKEN,
                SPOTIFY_REDIRECT_URI);
        builder.setScopes(SPOTIFY_SCOPE);
        AuthenticationRequest loginRequest = builder.build();
        AuthenticationClient.openLoginActivity(
                MainActivity.this,
                SPOTIFY_AUTH_REQUEST_CODE,
                loginRequest);
    }

    private void connectSpotify() {
        // Build connection parameters.
        ConnectionParams connectionParams = new ConnectionParams.Builder(SPOTIFY_CLIENT_ID)
                .setRedirectUri(SPOTIFY_REDIRECT_URI)
                .showAuthView(false)
                .build();

        // Connect to SpotifyAppRemote.
        SpotifyAppRemote.connect(getApplication().getApplicationContext(), connectionParams, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote appRemote) {
                Log.d(LOG_TAG, "Spotify app remote connected.");
                if (appRemote != null) {
                    spotifyAppRemote = appRemote;

                    // Need PlayerApi to access player state.
                    final PlayerApi playerApi = spotifyAppRemote.getPlayerApi();

                    // Subscribe to player state and update if it changes.
                    playerApi.subscribeToPlayerState().setEventCallback(playerState -> {
                        if (playerState.track != null) {
                            // Initialize onClickListeners for control button.
                            playerControlButton.setOnClickListener(null);
                            playerControlButton.setOnClickListener(view -> {
                                if (playerState.isPaused) {
                                    playerApi.resume();
                                } else {
                                    playerApi.pause();
                                }
                            });

                            // Update track metadata on player views.
                            track = playerState.track;
                            String title = track.name;
                            List<Artist> artists = track.artists;
                            ImageUri albumImageUri = track.imageUri;

                            setAlbumImage(albumImageUri);
                            setTrackTitleArtists(title, artists);

                            // Update progress bar and play/pause state.
                            long duration = track.duration;
                            long position = playerState.playbackPosition;
                            setProgressBar(position, duration);
                            if (playerState.isPaused) {
                                showPause();
                                stopProgressBar(progressBarTimer);
                            } else {
                                showPlay();
                                runProgressBar(progressBarTimer, getProgressInterval(duration));
                            }

                            player.setVisibility(View.VISIBLE);
                            if (snapchatButton.hasOnClickListeners()) snapchatButton.setOnClickListener(null);
                            snapchatButton.setOnClickListener(view -> sendSnapchat());
                        }
                    });
                }
            }
            @Override
            public void onFailure(Throwable error) {
                Log.e(LOG_TAG, error.getMessage(), error);
                if (error instanceof NotLoggedInException ||
                        error instanceof UserNotAuthorizedException) {
                    // TODO: Show login dialog.
                } else if (error instanceof CouldNotFindSpotifyApp) {
                    // TODO: Show dialog to download Spotify.
                }
            }
        });
    }

    private void setProgressBar(long position, long duration) {
        if (progressBarTimer != null) progressBarTimer.cancel();
        progressBarTimer = new Timer();
        playerProgressBar.setMax(PROGRESS_BAR_TIME_UNIT);
        playerProgressBar.setProgress(getCurrentProgress(position, duration));
    }

    private void setAlbumImage(ImageUri imageUri) {
        if (imageUri != null) {
            CallResult<Bitmap> result = spotifyAppRemote
                    .getImagesApi()
                    .getImage(imageUri, Image.Dimension.SMALL);
            result.setResultCallback(bitmap -> {
                albumCoverBitmap = bitmap;
                playerAlbumCoverImageView.setImageBitmap(bitmap);
            });
        }
    }

    private void setTrackTitleArtists(String title, List<Artist> artists) {
        trackTitleTextView.setText(title);
        trackArtistsTextView.setText(getArtistsString(artists));
    }

    private String getArtistsString(List<Artist> artists) {
        StringBuilder result = new StringBuilder();
        if (artists != null) {
            for (int i = 0; i < artists.size(); i++) {
                Artist artist = artists.get(i);
                if (artist != null) {
                    result.append(artist.name);
                }
                if (i != artists.size() - 1) {
                    result.append(", ");
                }
            }
        }
        return result.toString();
    }

    private void showPlay() {
        int padding = getResources().getDimensionPixelOffset(R.dimen.control_default_padding);
        playerControlButton.setPadding(padding, padding, padding, padding);
        playerControlButton.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.pause_white));
    }

    private void showPause() {
        // Need to add left padding to manually center 'play' image.
        int leftPadding = getResources().getDimensionPixelOffset(R.dimen.play_left_padding);
        int padding = getResources().getDimensionPixelOffset(R.dimen.control_default_padding);
        playerControlButton.setPadding(leftPadding, padding, padding, padding);
        playerControlButton.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.play_white));
    }

    private int getCurrentProgress(long position, long duration) {
        return (int) ((position / (float) duration) * PROGRESS_BAR_TIME_UNIT);
    }

    private long getProgressInterval(long duration) {
        return duration / PROGRESS_BAR_TIME_UNIT;
    }

    private void runProgressBar(Timer timer, long interval) {
        if (timer != null) {
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    playerProgressBar.setProgress(playerProgressBar.getProgress() + 1);
                }
            }, 0, interval);
        }
    }

    private void stopProgressBar(Timer timer) {
        if (timer != null) timer.cancel();
    }

    private String getCaptionText() {
        return track.name + " - " + track.artist.name;
    }
}

