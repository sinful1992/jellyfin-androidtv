BEGIN { FS="," }
/^Flags,/ { for (i=1;i<=NF;i++) col[$i]=i; have=1; next }
have && /^[01],/ {
  if ($1 != 0) next
  n++
  v(n,"total",   $col["FrameCompleted"] - $col["IntendedVsync"])
  v(n,"input",   $col["AnimationStart"] - $col["HandleInputStart"])
  v(n,"anim",    $col["PerformTraversalsStart"] - $col["AnimationStart"])
  v(n,"layout",  $col["DrawStart"] - $col["PerformTraversalsStart"])
  v(n,"draw",    $col["SyncQueued"] - $col["DrawStart"])
  v(n,"sync",    $col["IssueDrawCommandsStart"] - $col["SyncStart"])
  v(n,"issue",   $col["SwapBuffers"] - $col["IssueDrawCommandsStart"])
  v(n,"swap",    $col["FrameCompleted"] - $col["SwapBuffers"])
  v(n,"gpu",     $col["GpuCompleted"] - $col["IssueDrawCommandsStart"])
}
function v(i,k,x) { d[k,i]=x/1000000.0 }
END {
  split("total input anim layout draw sync issue swap gpu", ks, " ")
  printf "frames (complete): %d\n", n
  for (j=1;j<=9;j++) {
    k=ks[j]; delete a
    for (i=1;i<=n;i++) a[i]=d[k,i]
    m=med(a,n); p9=pct(a,n,0.9)
    printf "%-8s median %7.2f ms   p90 %7.2f ms\n", k, m, p9
  }
}
function med(arr,cnt,  b,i,c) { return pct(arr,cnt,0.5) }
function pct(arr,cnt,p,  b,i,c,idx) {
  c=0; for (i=1;i<=cnt;i++) { c++; b[c]=arr[i] }
  asort_(b,c)
  idx=int(p*c); if (idx<1) idx=1; if (idx>c) idx=c
  return b[idx]
}
function asort_(b,c,  i,j,t) {
  for (i=1;i<c;i++) for (j=1;j<=c-i;j++) if (b[j]>b[j+1]) { t=b[j]; b[j]=b[j+1]; b[j+1]=t }
}
