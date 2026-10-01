package com.mobilegroup20.modelpilot.contract.model;

import java.util.ArrayList;
import java.util.List;

public class ForumPage<T> {
    public List<T> items = new ArrayList<>();
    /** Opaque server cursor; null means there are no more items. */
    public String nextCursor;
}
