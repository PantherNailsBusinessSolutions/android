package p1.p2;

import android.annotation.TargetApi;
import android.content.Context;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.widget.GridLayout;

public class Class extends <error descr="Class requires API level 14 (current min is 1): android.widget.GridLayout">GridLayout</error> implements
                                      <error descr="Class requires API level 11 (current min is 1): android.view.View.OnSystemUiVisibilityChangeListener">View.OnSystemUiVisibilityChangeListener</error>, <error descr="Class requires API level 11 (current min is 1): android.view.View.OnLayoutChangeListener">OnLayoutChangeListener</error> {

  public Class(Context context) {
    <error descr="Call requires API level 14 (current min is 1): new android.widget.GridLayout">super</error>(context);
  }

  @Override
  public void onSystemUiVisibilityChange(int visibility) {
  }

  @Override
  public void onLayoutChange(View v, int left, int top, int right,
                             int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
  }
}