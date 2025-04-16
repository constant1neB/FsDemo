package com.example.fsdemo.service;

public interface VideoSecurityService {

    /**
     * Checks if the given username is the owner of the video.
     *
     * @param videoId  The ID of the video.
     * @param username The username to check.
     * @return true if the user is the owner, false otherwise.
     */
    boolean isOwner(Long videoId, String username);

    /**
     * Checks if the given user can view the video (must be the owner).
     *
     * @param videoId  The ID of the video.
     * @param username The username attempting to view.
     * @return true if viewing is allowed (user is owner), false otherwise.
     */
    boolean canView(Long videoId, String username);

    /**
     * Checks if the given user can delete the video (must be the owner).
     *
     * @param videoId  The ID of the video.
     * @param username The username attempting to delete.
     * @return true if deletion is allowed, false otherwise.
     */
    boolean canDelete(Long videoId, String username);
}
