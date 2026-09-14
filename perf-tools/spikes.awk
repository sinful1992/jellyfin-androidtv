BEGIN { FS="," }
/^Flags,/ { for (i=1;i<=NF;i++) col[$i]=i; have=1; next }
have && /^[01],/ {
  if ($1 != 0) next
  n++
  ms(n,"total",   $col["FrameCompleted"] - $col["IntendedVsync"])
  ms(n,"anim",    $col["PerformTraversalsStart"] - $col["AnimationStart"])
  ms(n,"layout",  $col["DrawStart"] - $col["PerformTraversalsStart"])
  ms(n,"record",  $col["SyncQueued"] - $col["DrawStart"])
  ms(n,"sync",    $col["IssueDrawCommandsStart"] - $col["SyncStart"])
  ms(n,"issue",   $col["SwapBuffers"] - $col["IssueDrawCommandsStart"])
  ms(n,"deq",     $col["DequeueBufferDuration"])
  ms(n,"que",     $col["QueueBufferDuration"])
  ms(n,"swap",    $col["FrameCompleted"] - $col["SwapBuffers"])
  ms(n,"gpuall",  $col["GpuCompleted"] - $col["IssueDrawCommandsStart"])
  ms(n,"gpuexec", $col["GpuCompleted"] - $col["CommandSubmissionCompleted"])
}
function ms(i,k,x) { d[k,i]=x/1000000.0 }
END {
  nk=split("total anim layout record sync issue deq que swap gpuall gpuexec", ks, " ")
  printf "%s  frames=%d\n", FILENAME, n
  printf "%-8s %8s %8s %8s %8s\n", "phase", "median", "p90", "p99", "max"
  for (j=1;j<=nk;j++) {
    k=ks[j]; delete a
    for (i=1;i<=n;i++) a[i]=d[k,i]
    printf "%-8s %8.2f %8.2f %8.2f %8.2f\n", k, pct(a,n,0.5), pct(a,n,0.9), pct(a,n,0.99), pct(a,n,1.0)
  }
  # worst frames by total
  printf "\nworst frames by total (idx total anim layout record sync issue deq que swap gpuall gpuexec)\n"
  for (i=1;i<=n;i++) { t[i]=d["total",i]; idx[i]=i }
  for (i=1;i<n;i++) for (j2=1;j2<=n-i;j2++) if (t[j2]<t[j2+1]) { tmp=t[j2];t[j2]=t[j2+1];t[j2+1]=tmp; tmp=idx[j2];idx[j2]=idx[j2+1];idx[j2+1]=tmp }
  lim = (n<8?n:8)
  for (i=1;i<=lim;i++) {
    f=idx[i]
    printf "%4d", f
    for (j=1;j<=nk;j++) printf " %8.2f", d[ks[j],f]
    printf "\n"
  }
  printf "\n"
}
function pct(arr,cnt,p,  b,i,c,ix) {
  c=0; for (i=1;i<=cnt;i++) { c++; b[c]=arr[i] }
  srt(b,c)
  ix=int(p*c+0.5); if (ix<1) ix=1; if (ix>c) ix=c
  return b[ix]
}
function srt(b,c,  i,j,t2) {
  for (i=1;i<c;i++) for (j=1;j<=c-i;j++) if (b[j]>b[j+1]) { t2=b[j]; b[j]=b[j+1]; b[j+1]=t2 }
}
