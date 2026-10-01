package com.mobilegroup20.modelpilot.contract.model;

import java.util.ArrayList;
import java.util.List;

/** Only user-editable fields. Identity, counters and official status are server-owned. */
public class PostDraft {
    public String title = "";
    public String body = "";
    public List<String> imageIds = new ArrayList<>();
}
